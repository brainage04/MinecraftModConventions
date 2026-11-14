package io.github.brainage04.minecraftmodconventions;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryUtil;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;

/**
 * Records the game's own sound mix. While the recorder captures the game, the sound engine opens an OpenAL Soft
 * loopback device ({@code ALC_SOFT_loopback}, {@code SOFTLoopback} in LWJGL 3.4.3, which 26.3 ships) instead of an
 * output device, so the game plays to nothing and this class renders its mix on the wall clock: sample {@code n}
 * of the recording is the mix at {@code t0 + n / 48000}, the same clock the picture's slots follow. Rendering never
 * stops while the device is open: OpenAL Soft advances sources only as samples are rendered, so a paused render
 * would leave sounds playing forever. Samples before the capture starts are rendered and discarded.
 */
public final class GameCaptureAudio {
    public static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = CHANNELS * Short.BYTES;
    private static final long INTERVAL_NANOS = 5_000_000L;
    private static final Path AUDIO = path("CAPTURE_AUDIO");
    private static final Object LOCK = new Object();
    private static final Object WRITE_LOCK = new Object();

    private static Thread renderer;
    private static volatile boolean running;
    /** Set when a renderer could not be stopped within its join: it still owns the sound pipe. */
    private static boolean stuck;
    /** Where the recording's sound starts, and the clock to place a device's samples by; null until it starts. */
    private static volatile Start start;
    /** The sound pipe, shared by every device the sound engine opens, so a reload does not end the stream. */
    private static OutputStream out;
    // Written under WRITE_LOCK, read without it: the sound writer holds that lock while it waits on the pipe, and the
    // test thread that describes the recording must not wait for ffmpeg.
    private static volatile long writtenFrames;
    private static volatile long silentFrames;

    private GameCaptureAudio() {}

    /** True when the sound engine should open a loopback device for the recording. */
    public static boolean isEnabled() {
        return GameCapture.isEnabled() && AUDIO != null;
    }

    /**
     * The context attributes a loopback device needs: its sample format and rate, then {@code attributes} (a
     * zero-terminated list), in a buffer that lives as long as {@code memory}'s frame.
     */
    public static IntBuffer loopbackAttributes(IntBuffer attributes, IntBuffer memory) {
        memory.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT);
        memory.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
        memory.put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE);
        for (int i = attributes.position(); i < attributes.limit() && attributes.get(i) != 0; i += 2) {
            memory.put(attributes.get(i)).put(attributes.get(i + 1));
        }
        return memory.put(0).flip();
    }

    /** The number of ints {@link #loopbackAttributes} may write for {@code attributes}. */
    public static int loopbackAttributeCapacity(IntBuffer attributes) {
        return attributes.remaining() + 7;
    }

    /** Opens a loopback device that renders 48 kHz 16-bit stereo; 0 when OpenAL Soft cannot. */
    public static long openDevice() {
        long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
        if (device != 0 && !SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, SAMPLE_RATE,
                SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
            ALC10.alcCloseDevice(device);
            return 0;
        }
        return device;
    }

    /**
     * The failure a game take ends with when this libopenal has no {@code ALC_SOFT_loopback}: a recording that
     * cannot render the mix has no sound to fall back on, so the message names the missing extension and the
     * recording route that does not need it. Printed on the recorder's failure line, which fails the take at once.
     */
    public static IllegalStateException loopbackUnavailable() {
        String message = "Failed to open OpenAL device: the recording renders the game's own mix through an OpenAL"
                + " Soft loopback device (ALC_SOFT_loopback), and this libopenal does not offer one. Record with"
                + " GTR_RECORDING_CAPTURE=x11grab, whose sound comes from a PulseAudio sink's monitor, or use a"
                + " libopenal built with the extension.";
        GameCapture.reportFailure(message);
        return new IllegalStateException(message);
    }

    /** Starts rendering the device's mix; call once its context is current. */
    public static void deviceOpened(long device) {
        synchronized (LOCK) {
            stopRendering();
            if (stuck) {
                return;
            }
            running = true;
            renderer = new Thread(() -> render(device), "Recording sound");
            renderer.setDaemon(true);
            renderer.start();
        }
        System.out.println("[CLIENT_GAMETEST_RECORDING] Game sound renders into the recording through an OpenAL loopback device");
    }

    /** Stops rendering before the sound engine closes the device. */
    public static void deviceClosing() {
        synchronized (LOCK) {
            stopRendering();
        }
    }

    /**
     * From now on the rendered mix goes to the recording, sample 0 at {@code captureStartUs} on the picture's
     * {@code anchor}, so the sound and the picture tell time from the same wall-clock reading.
     */
    static void startWriting(long captureStartUs, GameCapture.Anchor anchor) {
        start = new Start(captureStartUs, anchor);
    }

    /** Adds the sound capture's counts to the recording's boundary evidence. */
    static void describe(Properties evidence) {
        if (start == null) {
            return;
        }
        evidence.setProperty("gameSoundFramesWritten", Long.toString(writtenFrames));
        evidence.setProperty("gameSoundSilentFrames", Long.toString(silentFrames));
    }

    private static void stopRendering() {
        running = false;
        if (renderer != null) {
            try {
                // The writer can be blocked in out.write on a full pipe while holding WRITE_LOCK; waiting for it
                // without a bound would hang the client's shutdown behind ffmpeg. It is a daemon thread, and the
                // sound it still writes is the recording's tail.
                renderer.join(1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            if (renderer.isAlive()) {
                // It dies with the JVM. A second renderer would write into the same pipe behind it, and the sound
                // it still writes is the recording's tail, so nothing else starts one.
                stuck = true;
                System.out.println("[CLIENT_GAMETEST_RECORDING] The sound writer did not stop within a second.");
                return;
            }
            renderer = null;
        }
    }

    private static void render(long device) {
        long renderStartNanos = System.nanoTime();
        ByteBuffer buffer = MemoryUtil.memAlloc(SAMPLE_RATE * BYTES_PER_FRAME);
        byte[] bytes = new byte[buffer.capacity()];
        long rendered = 0;
        // The recording's sample index of this device's rendered sample 0, once the capture has started.
        long offset = Long.MIN_VALUE;
        boolean writing = true;
        try {
            while (running) {
                long due = (System.nanoTime() - renderStartNanos) * SAMPLE_RATE / 1_000_000_000L;
                while (rendered < due) {
                    int count = (int) Math.min(due - rendered, SAMPLE_RATE);
                    buffer.clear();
                    SOFTLoopback.alcRenderSamplesSOFT(device, buffer, count);
                    long first = rendered;
                    rendered += count;
                    Start capture = start;
                    if (!writing || capture == null || AUDIO == null) {
                        continue;
                    }
                    if (offset == Long.MIN_VALUE) {
                        offset = SoundClock.deviceStartIndex(capture.anchor(), capture.startUs(), renderStartNanos);
                    }
                    try {
                        write(buffer, bytes, count, offset + first);
                    } catch (IOException exception) {
                        // The recorder stops ffmpeg once it has the sound it needs, which closes the pipe.
                        System.out.println("[CLIENT_GAMETEST_RECORDING] The sound pipe closed: " + exception.getMessage());
                        writing = false;
                    }
                }
                LockSupport.parkNanos(INTERVAL_NANOS);
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    /**
     * Writes rendered frames whose recording index is {@code firstIndex} onwards: frames before the capture's start
     * are dropped, and a gap before the first rendered frame is filled with silence, so the recording's sample
     * {@code n} stays at {@code t0 + n / rate}.
     */
    private static void write(ByteBuffer buffer, byte[] bytes, int count, long firstIndex) throws IOException {
        synchronized (WRITE_LOCK) {
            if (out == null) {
                out = new FileOutputStream(AUDIO.toFile());
            }
            long gap = firstIndex - writtenFrames;
            if (gap > 0) {
                byte[] silence = new byte[(int) Math.min(gap, SAMPLE_RATE) * BYTES_PER_FRAME];
                for (long left = gap; left > 0; left -= SAMPLE_RATE) {
                    out.write(silence, 0, (int) Math.min(left, SAMPLE_RATE) * BYTES_PER_FRAME);
                }
                writtenFrames += gap;
                silentFrames += gap;
            }
            long skip = writtenFrames - firstIndex;
            if (skip >= count) {
                return;
            }
            int length = (count - (int) skip) * BYTES_PER_FRAME;
            buffer.get((int) skip * BYTES_PER_FRAME, bytes, 0, length);
            out.write(bytes, 0, length);
            writtenFrames += count - skip;
        }
    }

    private record Start(long startUs, GameCapture.Anchor anchor) {}

    /** The recording's sample clock. */
    static final class SoundClock {
        private SoundClock() {}

        /** The index of the sample at {@code timeUs} in a recording whose sample 0 is at {@code startUs}. */
        static long frameIndex(long timeUs, long startUs) {
            return Math.floorDiv((timeUs - startUs) * SAMPLE_RATE, 1_000_000L);
        }

        /**
         * The recording's index of the sample a device rendered first, at the monotonic {@code deviceStartNanos}: the
         * device's start is placed on the picture's {@code anchor}, not on a wall-clock reading of its own.
         */
        static long deviceStartIndex(GameCapture.Anchor anchor, long captureStartUs, long deviceStartNanos) {
            return frameIndex(anchor.us(deviceStartNanos), captureStartUs);
        }
    }

    private static Path path(String suffix) {
        String value = System.getenv("CLIENT_GAMETEST_RECORDING_" + suffix);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
