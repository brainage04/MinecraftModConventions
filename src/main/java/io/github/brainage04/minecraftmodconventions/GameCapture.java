package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Records the game's own picture: each frame is copied out of the main render target before it is presented, the
 * way vanilla's screenshot and Tracy frame capture read it ({@code Screenshot.java:76-130},
 * {@code TracyFrameCapture.java:65-110} in the 26.3 sources), and written as raw RGBA to the recorder's ffmpeg. The
 * copy is asynchronous: the GPU fills a mappable buffer, and a fenced task marks it ready a frame or two later.
 *
 * <p>The video runs on the capture clock the recorder writes ({@code t0} and the capture rate): slot {@code k} is at
 * {@code t0 + k / fps} and shows the newest frame presented before it, so the video keeps the timing of a screen
 * grab without its tears. A present waits only while the present before it falls between the same two slots, so the
 * client presents at most once per slot and a late frame never waits.
 */
public final class GameCapture {
    private static final String PREFIX = "CLIENT_GAMETEST_RECORDING_";
    private static final Path CLOCK = path("CAPTURE_CLOCK");
    private static final Path VIDEO = path("CAPTURE_VIDEO");
    /** Copies in flight: the GPU finishes a copy a frame or two after it is issued. */
    private static final int BUFFERS = 4;
    /** Frames waiting for the writer; a full queue drops the newest frame instead of stalling the render thread. */
    private static final int QUEUED_FRAMES = 3;
    /** The main target's format: four bytes a pixel, which is what the copy buffers and ffmpeg's rgba read. */
    private static final int BYTES_PER_PIXEL = 4;
    private static final long CLOCK_POLL_NANOS = 50_000_000L;
    private static final long SPIN_NANOS = 500_000L;
    /**
     * How old a slot must be before it repeats the frame shown because no newer frame came: a frame reaches the
     * writer up to {@link #BUFFERS} presents after its own, once its copy is read back.
     */
    static final long HOLD_US = 250_000L;
    private static final Object LOCK = new Object();
    private static final Poll CLOCK_POLL = new Poll(CLOCK_POLL_NANOS);

    private static GameCapture active;
    /** Why this run cannot capture the game at all; reported once, then never retried. */
    private static String startFailure;

    private final Clock clock;
    /** The wall clock's reading when the capture started, and the monotonic reading it is anchored to. */
    private final long epochBaseUs;
    private final long nanoBase;
    private final GpuBuffer[] buffers = new GpuBuffer[BUFFERS];
    private final int[] states = new int[BUFFERS];
    private final long[] presentUs = new long[BUFFERS];
    private final long[] issued = new long[BUFFERS];
    private final BlockingQueue<byte[]> spare = new ArrayBlockingQueue<>(QUEUED_FRAMES + 2);
    private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(QUEUED_FRAMES);
    private final Thread writer;
    private long frameCount;
    private long dropped;
    private long readbacks;
    private long readbackNanosTotal;
    private long readbackNanosMax;
    private volatile long slotsWritten;
    private volatile long repeatedSlots;
    /** The last present's time, on the render thread only. */
    private long lastPresentUs = Long.MIN_VALUE;
    private volatile String failure;
    /** Set when the writer sees the pipe close: the recording has ended and no more frames are wanted. */
    private volatile boolean stopped;

    private GameCapture(Clock clock) {
        this.clock = clock;
        long nanos = System.nanoTime();
        this.epochBaseUs = epochMicros();
        this.nanoBase = nanos;
        long size = (long) clock.width() * clock.height() * BYTES_PER_PIXEL;
        for (int i = 0; i < BUFFERS; i++) {
            buffers[i] = RenderSystem.getDevice().createBuffer(() -> "Recording capture", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
        }
        for (int i = 0; i < QUEUED_FRAMES + 2; i++) {
            spare.add(new byte[(int) size]);
        }
        writer = new Thread(this::write, "Recording capture writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** True when the recorder captures the game's own picture and sound instead of grabbing the screen. */
    public static boolean isEnabled() {
        return CLOCK != null && VIDEO != null;
    }

    /**
     * Call right before the present, on the render thread: holds the present until the next slot when the present
     * before it still owns the current one, then copies the frame out of {@code target}. A capture that has failed
     * copies nothing more, and one whose pipe has closed neither copies nor waits; the recorder fails a failed take
     * with the reason.
     */
    public static void beforePresent(RenderTarget target) {
        GameCapture capture = capture();
        GpuTexture texture = target.getColorTexture();
        if (capture == null || texture == null || capture.failure != null || capture.stopped) {
            return;
        }
        if (target.width != capture.clock.width() || target.height != capture.clock.height()) {
            capture.fail("The client's frame is " + target.width + "x" + target.height + ", not the recording's "
                    + capture.clock.width() + "x" + capture.clock.height());
            return;
        }
        String formatProblem = captureFormatProblem(texture.getFormat().blockSize());
        if (formatProblem != null) {
            capture.fail(formatProblem);
            return;
        }
        capture.frame(texture, capture.awaitPresentTime());
    }

    /** Adds the capture's counts to the recording's boundary evidence; nothing when the game is not captured. */
    public static void describe(Properties evidence) {
        GameCapture capture;
        synchronized (LOCK) {
            capture = active;
        }
        if (capture == null) {
            return;
        }
        synchronized (capture) {
            evidence.setProperty("gameCaptureFrames", Long.toString(capture.frameCount));
            evidence.setProperty("gameCaptureDroppedFrames", Long.toString(capture.dropped));
            evidence.setProperty("gameCaptureReadbackMsMean", String.format(java.util.Locale.ROOT, "%.3f",
                    capture.readbacks == 0 ? 0.0 : capture.readbackNanosTotal / 1e6 / capture.readbacks));
            evidence.setProperty("gameCaptureReadbackMsMax", String.format(java.util.Locale.ROOT, "%.3f",
                    capture.readbackNanosMax / 1e6));
        }
        evidence.setProperty("gameCaptureSlots", Long.toString(capture.slotsWritten));
        evidence.setProperty("gameCaptureRepeatedSlots", Long.toString(capture.repeatedSlots));
        if (capture.failure != null) {
            evidence.setProperty("gameCaptureFailure", capture.failure);
        }
    }

    private static GameCapture capture() {
        if (!isEnabled()) {
            return null;
        }
        synchronized (LOCK) {
            if (active != null) {
                return active;
            }
            if (startFailure != null) {
                return null;
            }
            if (!CLOCK_POLL.due(System.nanoTime()) || !Files.isRegularFile(CLOCK)) {
                return null;
            }
            Clock clock = readClock(CLOCK);
            if (clock == null) {
                return null;
            }
            String backendProblem = backendProblem(RenderSystem.getDevice().getDeviceInfo().backendName());
            if (backendProblem != null) {
                startFailure = backendProblem;
                reportFailure(startFailure);
                return null;
            }
            active = new GameCapture(clock);
            GameCaptureAudio.startWriting(active.clock.startUs());
            return active;
        }
    }

    /**
     * The capture clock in {@code file}, or null when it is not there whole yet. The recorder replaces the file in
     * one move, but a poll may still catch it missing, empty or part-read, and the next poll must retry: an exception
     * here would be thrown out of {@code Minecraft.renderFrame} on the render thread and crash the client.
     */
    static Clock readClock(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException exception) {
            return null;
        }
        try {
            return Clock.parse(text);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Waits for the time {@link Clock#presentAtUs} gives and returns the present's time. */
    private long awaitPresentTime() {
        long now = nowUs();
        long waitUs = clock.presentAtUs(lastPresentUs, now) - now;
        long until = System.nanoTime() + waitUs * 1000L;
        long remaining;
        while ((remaining = until - System.nanoTime()) > 0) {
            if (remaining > SPIN_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
        lastPresentUs = nowUs();
        return lastPresentUs;
    }

    /**
     * The capture's wall-clock time, anchored to one monotonic reading when the capture started. The sound's samples
     * follow {@link System#nanoTime()} (see {@link GameCaptureAudio}), so a wall-clock step during a take would
     * otherwise move the video timeline against the sound by the size of the step.
     */
    private long nowUs() {
        return anchoredUs(epochBaseUs, nanoBase, System.nanoTime());
    }

    /** The wall-clock microsecond time {@code nowNanos} of a clock anchored at {@code epochBaseUs}/{@code nanoBase}. */
    static long anchoredUs(long epochBaseUs, long nanoBase, long nowNanos) {
        return epochBaseUs + (nowNanos - nanoBase) / 1000L;
    }

    private synchronized void frame(GpuTexture texture, long presentTimeUs) {
        if (stopped) {
            return;
        }
        for (int pass = 0; pass < BUFFERS; pass++) {
            int oldest = -1;
            for (int i = 0; i < BUFFERS; i++) {
                if (states[i] == 2 && (oldest < 0 || issued[i] < issued[oldest])) {
                    oldest = i;
                }
            }
            if (oldest < 0) {
                break;
            }
            hand(oldest);
        }
        int slot = -1;
        for (int i = 0; i < BUFFERS; i++) {
            if (states[i] == 0) {
                slot = i;
                break;
            }
        }
        frameCount++;
        if (slot < 0) {
            dropped++;
            return;
        }
        int index = slot;
        states[index] = 1;
        presentUs[index] = presentTimeUs;
        issued[index] = frameCount;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffers[index], 0L, () -> {
            synchronized (this) {
                states[index] = 2;
            }
        }, 0);
    }

    private void hand(int index) {
        byte[] bytes = spare.poll();
        if (bytes == null) {
            dropped++;
        } else {
            long start = System.nanoTime();
            try (GpuBufferSlice.MappedView view = buffers[index].map(true, false)) {
                view.data().get(0, bytes);
            }
            long took = System.nanoTime() - start;
            readbacks++;
            readbackNanosTotal += took;
            readbackNanosMax = Math.max(readbackNanosMax, took);
            if (!frames.offer(new Frame(bytes, presentUs[index]))) {
                spare.add(bytes);
                dropped++;
            }
        }
        states[index] = 0;
    }

    private void write() {
        long periodNanos = 1_000_000_000L / clock.framesPerSecond();
        try (OutputStream out = new FileOutputStream(VIDEO.toFile())) {
            byte[] shown = null;
            boolean shownWritten = false;
            long slot = 0;
            while (true) {
                Frame frame = frames.poll(periodNanos, TimeUnit.NANOSECONDS);
                if (frame == null && shown == null) {
                    continue;
                }
                // Every slot before this frame's present shows the frame before it (the first frame for the slots
                // before any frame arrived).
                long until = clock.slotsToFill(frame == null ? null : frame.presentUs(), nowUs());
                byte[] fill = shown == null ? frame.bytes() : shown;
                for (; slot < until; slot++) {
                    out.write(fill);
                    slotsWritten++;
                    if (fill == shown) {
                        if (shownWritten) {
                            repeatedSlots++;
                        }
                        shownWritten = true;
                    }
                }
                if (frame != null) {
                    if (shown != null) {
                        spare.add(shown);
                    }
                    shown = frame.bytes();
                    shownWritten = false;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            // The recorder stops ffmpeg once it has the video it needs, which closes the pipe under the writer;
            // a capture that ends early fails in the recorder, which sees the video stop.
            System.out.println("[CLIENT_GAMETEST_RECORDING] The capture pipe closed: " + exception.getMessage());
        } finally {
            stopped = true;
        }
    }

    /**
     * Why a frame whose format has {@code blockSize} bytes a pixel cannot be captured, or null. The copy buffers
     * hold four bytes a pixel and ffmpeg is told {@code rgba}, the main target's format ({@code MainTarget.java:18}
     * in the 26.3 sources) and the size vanilla's screenshot asks the format for ({@code Screenshot.java:85}).
     */
    static String captureFormatProblem(int blockSize) {
        if (blockSize == BYTES_PER_PIXEL) {
            return null;
        }
        return "The client's frame is " + blockSize + " bytes a pixel, and the recording reads "
                + BYTES_PER_PIXEL + " (RGBA8): this graphics format is not one the capture can record.";
    }

    /**
     * Why the capture cannot run on the graphics backend the client uses, or null on the one it was verified on.
     * The copy reads the main target's rows and the video flips them ({@code vflip}), which is GL's bottom-up
     * order and what vanilla's screenshot does ({@code Screenshot.java:102} in the 26.3 sources). No Vulkan run
     * has checked what {@code VulkanCommandEncoder.copyTextureToBuffer} hands back, so a game take refuses a
     * backend it has not been tried on instead of shipping a picture that may be upside down.
     */
    static String backendProblem(String backendName) {
        if ("OpenGL".equals(backendName)) {
            return null;
        }
        return "The game capture runs only on OpenGL, and this client renders through "
                + (backendName == null || backendName.isBlank() ? "a backend with no name" : backendName)
                + ": its frames are flipped assuming GL's row order, which no Vulkan run has checked. Record with "
                + "GTR_RECORDING_CAPTURE=x11grab, or run the client on OpenGL.";
    }

    private void fail(String message) {
        if (failure == null) {
            failure = message;
            reportFailure(message);
        }
    }

    /** Prints the reason on the recorder's failure line, which fails the take with it. */
    private static void reportFailure(String message) {
        // The recorder reads this line from the client's output; see RecordClientGameTestTask.CAPTURE_FAILURE_LINE.
        System.err.println("[CLIENT_GAMETEST_RECORDING] CAPTURE_FAILURE: " + message);
    }

    private record Frame(byte[] bytes, long presentUs) {}

    /** The capture's start, rate and frame size, as the recorder writes them: {@code t0Us fps width height}. */
    record Clock(long startUs, int framesPerSecond, int width, int height) {
        static Clock parse(String text) {
            String[] fields = text.strip().split("\\s+");
            if (fields.length != 4) {
                throw new IllegalArgumentException("Expected 't0Us fps width height' in the capture clock, not " + text);
            }
            return new Clock(Long.parseLong(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]));
        }

        /** The slots at or before {@code timeUs}: slot {@code k} is at {@code startUs + k / fps}. */
        static long slotsBefore(long timeUs, long startUs, int framesPerSecond) {
            if (timeUs < startUs) {
                return 0;
            }
            return (timeUs - startUs) * framesPerSecond / 1_000_000L + 1;
        }

        /**
         * The slots the writer can fill: those before a new frame's present, or, while no frame comes (the client is
         * not presenting, such as while a lockstep test step holds it), those more than {@link #HOLD_US} old, which
         * repeat the frame shown the way a screen grab would. Without them ffmpeg, which reads its two inputs
         * interleaved, stops reading the sound, and the sound writer blocks with it.
         */
        long slotsToFill(Long presentUs, long nowUs) {
            return slotsBefore(presentUs != null ? presentUs : nowUs - HOLD_US, startUs, framesPerSecond);
        }

        /**
         * When a present may go: now, unless the present before it ({@code Long.MIN_VALUE} for none) falls between
         * the same two slots as now, and then at the next slot, so every present gets a slot of its own.
         */
        long presentAtUs(long lastPresentUs, long nowUs) {
            if (lastPresentUs == Long.MIN_VALUE || lastPresentUs < startUs) {
                return nowUs;
            }
            long slot = (lastPresentUs - startUs) * framesPerSecond / 1_000_000L;
            return Math.max(nowUs, startUs + Math.ceilDiv((slot + 1) * 1_000_000L, framesPerSecond));
        }
    }

    /** Says when a check that runs every frame should look again, at most once per {@code intervalNanos}. */
    static final class Poll {
        private final long intervalNanos;
        private long nextNanos = Long.MIN_VALUE;

        Poll(long intervalNanos) {
            this.intervalNanos = intervalNanos;
        }

        boolean due(long nowNanos) {
            if (nextNanos != Long.MIN_VALUE && nowNanos - nextNanos < 0) {
                return false;
            }
            nextNanos = nowNanos + intervalNanos;
            return true;
        }
    }

    private static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000L;
    }

    private static Path path(String suffix) {
        String value = System.getenv(PREFIX + suffix);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
