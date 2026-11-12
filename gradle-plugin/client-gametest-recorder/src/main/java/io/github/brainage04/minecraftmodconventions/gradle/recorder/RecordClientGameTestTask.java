package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import io.github.brainage04.minecraftmodconventions.gradle.production.ClientGameTestOptions;
import io.github.brainage04.minecraftmodconventions.gradle.production.ClientLaunchEnvironment;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DisableCachingByDefault(because = "The task controls external Minecraft, Xvfb, PulseAudio, and ffmpeg processes.")
public abstract class RecordClientGameTestTask extends DefaultTask {
    /**
     * Encoded packets the muxer may hold while a write to the raw capture blocks. ffmpeg's default of 8
     * (with the queues upstream, about 0.6 s of capture) lets a write that waits on a busy
     * disk's filesystem journal stop x11grab, which leaves a frame gap as long as the remaining wait. At about
     * 50 packets and 0.5 MB per second, 4096 packets cover over a minute of blocked writes in about 40 MB.
     */
    static final int MUX_QUEUE_PACKETS = 4096;

    /** Frames {@link #requireChangingPicture} decodes beside the four boundary images of a capture. */
    static final int PICTURE_SAMPLES = 8;

    /**
     * Raw frames ffmpeg's input thread may hold on a game take's video pipe. One rawvideo packet is one whole
     * frame, so this queue is what a slow encoder costs in memory: four 1920x1080 RGBA frames are 33 MB, where
     * ffmpeg's 64 would be 530 MB beside the client's own 75 MB, four GPU buffers and the muxer queue below.
     */
    static final int VIDEO_QUEUE_PACKETS = 4;

    /** Environment variable naming the raw capture's video encoder; see {@link #videoEncoder}. */
    static final String VIDEO_ENCODER_ENV = "GTR_RECORDING_VIDEO_ENCODER";

    /** Environment variable that turns a torn frame left after the repair from a failure into a warning. */
    static final String TEAR_CHECK_ENV = "GTR_RECORDING_TEAR_CHECK";

    /** Environment variable choosing what the recording captures: {@code game} (default) or {@code x11grab}. */
    static final String CAPTURE_SOURCE_ENV = "GTR_RECORDING_CAPTURE";

    /** The sample rate of the game's sound, which the client renders into the recording (its GameCaptureAudio). */
    static final int GAME_SOUND_RATE = 48_000;

    /** The line the client logs once its sound plays into the recording's loopback device. */
    static final String GAME_SOUND_LINE = "Game sound renders into the recording through an OpenAL loopback device";

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Internal
    public abstract DirectoryProperty getRunDirectory();

    /** The display in force, resolved from the Gradle property, the environment and the host's file. */
    @Input
    public abstract Property<String> getRecordingDisplay();

    /** The renderer a recording on that display must name, from the host's file; empty when unknown. */
    @Input
    public abstract Property<String> getExpectedRenderer();

    @Input
    public abstract Property<String> getRecordingAudioDeviceProjectProperty();

    @Input
    public abstract Property<String> getRunTaskName();

    @TaskAction
    public void record() {
        requireTools(List.of("ffmpeg", "ffprobe", "Xvfb", "xdpyinfo", "pactl"));

        File projectDir = getProjectDirectory().get().getAsFile();
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        File recordingDir = projectFile(projectDir, env("GTR_RECORDING_DIR", "build/recordings"));
        String recordingName = env("GTR_RECORDING_NAME", "client-gametest");
        String recordingProfile = env("GTR_RECORDING_PROFILE", env("CLIENT_GAMETEST_PROFILE", "showcase"));
        String recordingTrace = env("GTR_RECORDING_TRACE", "false");
        File runDir = getRunDirectory().get().getAsFile();
        String fps = env(ClientGameTestOptions.CAPTURE_FPS_ENV, Integer.toString(ClientGameTestOptions.DEFAULT_CAPTURE_FPS));
        int startWaitSeconds = parsePositiveInt(env("GTR_RECORDING_START_WAIT_SECONDS", "90"), "GTR_RECORDING_START_WAIT_SECONDS");
        String xvfbScreen = env(ClientGameTestOptions.RECORDER_SCREEN_ENV, ClientGameTestOptions.DEFAULT_RECORDER_SCREEN);
        String gpuDisplayRequest = getRecordingDisplay().getOrElse("");
        double prePadding = parsePadding(env("GTR_RECORDING_PRE_PADDING_SECONDS", "0.5"));
        double postPadding = parsePadding(env("GTR_RECORDING_POST_PADDING_SECONDS", "0.5"));
        int frameRate = parsePositiveInt(fps, ClientGameTestOptions.CAPTURE_FPS_ENV);
        String videoEncoderName = env(VIDEO_ENCODER_ENV, "libx264");
        List<String> videoEncoder = videoEncoder(videoEncoderName);
        boolean failOnTornFrames = failsOnTornFrames(env(TEAR_CHECK_ENV, "fail"));
        boolean gameCapture = capturesGame(env(CAPTURE_SOURCE_ENV, "game"));
        CommandResult encoderProbe = runCommand(encoderProbeCommand(videoEncoder), Map.of(), true);
        String encoderProblem = encoderProbeFailure(videoEncoderName, encoderProbe.status(), encoderProbe.output());
        if (encoderProblem != null) {
            throw new GradleException(encoderProblem);
        }
        if (truthy(env("GTR_RECORDING_AUDIO_SET_DEFAULT", "false"))) {
            throw new GradleException("Changing the desktop default audio sink is prohibited; recording uses a dedicated route.");
        }
        String safeRecordingName = sanitizePathComponent(recordingName);
        File output = new File(recordingDir, safeRecordingName + "-" + timestamp + ".mp4");
        File metadata = new File(recordingDir, safeRecordingName + "-" + timestamp + ".json");
        File startSignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".start");
        File windowSignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".window");
        File readySignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".ready");
        File stopSignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".stop");
        File completeSignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".complete");
        File boundaries = new File(recordingDir, safeRecordingName + "-" + timestamp + "-boundaries.properties");
        File rawCapture = new File(recordingDir, safeRecordingName + "-" + timestamp + "-raw.mkv");
        File progress = new File(recordingDir, safeRecordingName + "-" + timestamp + "-capture-progress.txt");
        File audioProgress = new File(recordingDir, safeRecordingName + "-" + timestamp + "-audio-packet-times.txt");
        File audioAnalysis = new File(recordingDir, safeRecordingName + "-" + timestamp + "-audio-analysis.txt");
        File captureClock = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".clock");
        File videoPipe = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + "-video.fifo");
        File audioPipe = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + "-audio.fifo");
        File alsoftConfig = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + "-alsoft.conf");
        String audioSinkName = env("GTR_RECORDING_AUDIO_SINK_NAME", "gametest_recorder_" + timestamp);
        String audioSource = audioSinkName + ".monitor";
        if (!audioSinkName.matches("[A-Za-z0-9_-]+")) {
            throw new GradleException("Audio sink name must contain only letters, digits, underscores and hyphens.");
        }
        String audioApplicationId = "mmc.recorder." + safeRecordingName + "." + timestamp;
        File keptRunDir = new File(recordingDir, safeRecordingName + "-" + timestamp + "-run");
        String recordingStartedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String xvfbDisplay = env("GTR_RECORDING_XVFB_DISPLAY", "");
        Process xvfbProcess = null;
        Process gradleProcess = null;
        Process ffmpegProcess = null;
        Integer ffmpegStatus = null;
        String audioSinkId = "";
        int gradleStatus = 1;
        boolean videoSaved = false;
        boolean audioSaved = false;
        boolean recordingStartObserved = false;
        boolean recordingStopObserved = false;
        boolean ffmpegExitedBeforeShutdown = false;
        AtomicBoolean soundInitialized = new AtomicBoolean();
        AtomicBoolean soundFailed = new AtomicBoolean();
        Map<String, Object> boundaryEvidence = new LinkedHashMap<>();
        boolean audioRouteVerified = false;
        String gpuDisplay = null;
        String gpuFallback = null;
        String glRenderer = "";
        String sdlVideoDriver = "";
        boolean audioDecoded = false;
        boolean audioNonSilent = false;
        String captureFailure = null;
        Map<String, String> processEnvironment = new LinkedHashMap<>(System.getenv());
        List<String> gradleCommand = List.of(
                "./gradlew",
                "--no-daemon",
                "-P" + getRecordingAudioDeviceProjectProperty().get() + "=" + audioSinkName,
                getRunTaskName().get()
        );

        recordingDir.mkdirs();
        deleteIfExists(startSignal.toPath());
        deleteIfExists(windowSignal.toPath());
        deleteIfExists(readySignal.toPath());
        deleteIfExists(stopSignal.toPath());
        deleteIfExists(completeSignal.toPath());
        deleteIfExists(boundaries.toPath());
        deleteIfExists(progress.toPath());
        deleteIfExists(captureClock.toPath());
        deleteIfExists(videoPipe.toPath());
        deleteIfExists(audioPipe.toPath());
        AtomicBoolean gameSoundCaptured = new AtomicBoolean();

        Path memoryCgroup = ownCgroup(Path.of("/proc/self/cgroup"), Path.of("/sys/fs/cgroup"));
        String previousMemoryLow = memoryCgroup == null ? null : protectMemory(memoryCgroup);
        if (previousMemoryLow != null) {
            getLogger().lifecycle("Protecting the recording job's memory up to its limit from sibling reclaim ({}).", memoryCgroup);
        }
        DisplayResolution display = null;
        try {
            display = startDisplay(gpuDisplayRequest, xvfbDisplay, xvfbScreen);
            xvfbDisplay = display.display();
            xvfbScreen = display.screen();
            xvfbProcess = display.process();
            gpuDisplay = display.gpuDisplay();
            gpuFallback = display.fallbackReason();
            String rateProblem = captureRateFailure(frameRate, gpuDisplay, gpuFallback);
            if (rateProblem != null) {
                throw new RecordingPreflightException(rateProblem);
            }

            audioSinkId = createVirtualAudioSink(audioSinkName);

            Files.writeString(alsoftConfig.toPath(), """
                    [general]
                    drivers = pulse

                    [pulse]
                    allow-moves = false
                    """, StandardCharsets.UTF_8);
            getLogger().lifecycle("Routing GameTest audio to isolated PulseAudio sink: {}", audioSinkName);
            if (!gameCapture) {
                getLogger().lifecycle("Recording audio from PulseAudio monitor source: {}", audioSource);
            }

            processEnvironment.clear();
            processEnvironment.putAll(System.getenv());
            processEnvironment.put("DISPLAY", xvfbDisplay);
            // The child build's options follow the display that was really used, also when a named one fell back.
            processEnvironment.put(ClientGpuCapture.DISPLAY_ENV, gpuDisplay == null ? "" : gpuDisplay);
            ClientLaunchEnvironment.useX11(processEnvironment);
            // The showcase window covers this whole display (see ClientGameTestOptions).
            processEnvironment.put(ClientGameTestOptions.RECORDER_SCREEN_ENV, xvfbScreen);
            processEnvironment.put("ALSOFT_CONF", alsoftConfig.getAbsolutePath());
            processEnvironment.put("ALSOFT_DRIVERS", "pulse");
            processEnvironment.put("ALSOFT_PULSE_DEFAULT", audioSinkName);
            processEnvironment.put("PULSE_SINK", audioSinkName);
            processEnvironment.put("PULSE_PROP", "application.id=" + audioApplicationId);
            // Vanilla selects an enumerated OpenAL device; environment defaults alone do not pin it.
            processEnvironment.put("GTR_RECORDING_AUDIO_DEVICE", audioSinkName);
            processEnvironment.put("GTR_RECORDING_MANAGED_XVFB", "true");
            // The client's options take their window size from this, so a named display's own size wins.
            processEnvironment.put("GTR_RECORDING_XVFB_SCREEN", xvfbScreen);
            processEnvironment.put("GTR_RECORDING_NAME", recordingName);
            processEnvironment.put("GTR_RECORDING_PROFILE", recordingProfile);
            processEnvironment.put("GTR_RECORDING_TRACE", recordingTrace);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_NAME", recordingName);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_PROFILE", recordingProfile);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_TRACE", recordingTrace);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_START_SIGNAL", startSignal.getAbsolutePath());
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_WINDOW", windowSignal.getAbsolutePath());
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_READY_SIGNAL", readySignal.getAbsolutePath());
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_STOP_SIGNAL", stopSignal.getAbsolutePath());
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_COMPLETE_SIGNAL", completeSignal.getAbsolutePath());
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_BOUNDARIES", boundaries.getAbsolutePath());
            if (gameCapture) {
                processEnvironment.put("CLIENT_GAMETEST_RECORDING_CAPTURE_CLOCK", captureClock.getAbsolutePath());
                processEnvironment.put("CLIENT_GAMETEST_RECORDING_CAPTURE_VIDEO", videoPipe.getAbsolutePath());
                processEnvironment.put("CLIENT_GAMETEST_RECORDING_CAPTURE_AUDIO", audioPipe.getAbsolutePath());
            }
            if (!recordingProfile.isBlank()) {
                processEnvironment.putIfAbsent("CLIENT_GAMETEST_PROFILE", recordingProfile);
            }

            getLogger().lifecycle("Starting client GameTest...");
            ProcessBuilder gradleBuilder = new ProcessBuilder(gradleCommand);
            gradleBuilder.directory(projectDir);
            gradleBuilder.redirectErrorStream(true);
            gradleBuilder.environment().clear();
            gradleBuilder.environment().putAll(processEnvironment);
            gradleProcess = gradleBuilder.start();
            Thread logThread = streamToStdout(gradleProcess, "client-gametest-output", line -> {
                if (line.contains("Sound engine started")) {
                    soundInitialized.set(true);
                }
                if (line.contains("Failed to open OpenAL device") || line.contains("Error starting SoundSystem")) {
                    soundFailed.set(true);
                }
                if (line.contains(GAME_SOUND_LINE)) {
                    gameSoundCaptured.set(true);
                }
            });

            getLogger().lifecycle("Waiting for client GameTest recording-start signal...");
            long signalDeadline = System.currentTimeMillis() + startWaitSeconds * 1000L;
            while (System.currentTimeMillis() < signalDeadline && !startSignal.exists() && gradleProcess.isAlive()) {
                sleep(100L);
            }

            recordingStartObserved = startSignal.exists();
            if (recordingStartObserved) {
                String videoSize = videoSizeFromXvfbScreen(xvfbScreen);
                List<String> ffmpegCommand;
                long captureStartFrames = 0;
                if (gameCapture) {
                    runCommand(List.of("mkfifo", videoPipe.getAbsolutePath(), audioPipe.getAbsolutePath()), Map.of(), false);
                    captureStartFrames = captureStartFrames(System.currentTimeMillis() * 1000L, frameRate);
                    boundaryEvidence.put("captureStartUs", captureStartFrames * 1_000_000L / frameRate);
                    getLogger().lifecycle("Recording the game's own picture and sound to {}", output.getAbsolutePath());
                    ffmpegCommand = gameCaptureCommand(frameRate, videoSize, captureStartFrames, videoPipe.getAbsolutePath(),
                            audioPipe.getAbsolutePath(), progress.getAbsolutePath(), audioProgress.getAbsolutePath(),
                            rawCapture.getAbsolutePath(), videoEncoder);
                } else {
                    // Vanilla centres a windowed client on its monitor (Window.java:118-119 in the 26.3
                    // sources), so a grab from the display's origin misses the window's right and bottom edges.
                    int[] grab = gpuDisplay == null
                            ? new int[] {0, 0}
                            : windowGrabOffset(windowSignal, xvfbDisplay, display.displayScreen(), videoSize);
                    if (gpuDisplay != null) {
                        boundaryEvidence.put("captureOffset", grab[0] + "," + grab[1]);
                        getLogger().lifecycle("Capturing the client's window at +{},{} of display {}.",
                                grab[0], grab[1], xvfbDisplay);
                    }
                    getLogger().lifecycle("Recording display {} to {}", xvfbDisplay, output.getAbsolutePath());
                    ffmpegCommand = captureCommand(fps, videoSize, xvfbDisplay, audioSource,
                            progress.getAbsolutePath(), audioProgress.getAbsolutePath(), rawCapture.getAbsolutePath(),
                            grab[0], grab[1], videoEncoder);
                }
                ProcessBuilder ffmpegBuilder = new ProcessBuilder(ffmpegCommand);
                ffmpegBuilder.directory(projectDir);
                ffmpegBuilder.redirectErrorStream(true);
                ffmpegBuilder.environment().putAll(processEnvironment);
                ffmpegProcess = ffmpegBuilder.start();
                streamToStdout(ffmpegProcess, "ffmpeg-output", _ -> {});
                if (gameCapture) {
                    // The client starts writing once it sees the clock; ffmpeg is already opening the pipes.
                    writeAtomically(captureClock.toPath(), captureStartFrames * 1_000_000L / frameRate + " " + frameRate
                            + " " + videoSize.replace('x', ' '));
                }
                waitForCaptureFrames(ffmpegProcess, progress);
                waitForAudioCoverage(ffmpegProcess, audioProgress, System.currentTimeMillis() / 1000.0);
                if (gameCapture) {
                    audioRouteVerified = gameSoundCaptured.get();
                } else {
                    verifyGameAudioRoute(audioSinkName, audioApplicationId);
                    audioRouteVerified = true;
                }
                sleep((long) Math.ceil(prePadding * 1000) + 100L);
                if (!ffmpegProcess.isAlive()) {
                    throw new GradleException("Capture exited during pre-world padding.");
                }
                Files.writeString(readySignal.toPath(), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
                boundaryEvidence.put("captureReadyEpochMs", System.currentTimeMillis());
                getLogger().lifecycle("[CLIENT_GAMETEST_BOUNDARY] CAPTURE_READY: encoded frames and isolated game audio verified");
            }

            while (gradleProcess.isAlive() && !stopSignal.exists()) {
                sleep(50L);
            }
            recordingStopObserved = stopSignal.exists();
            if (recordingStopObserved && ffmpegProcess != null) {
                ffmpegExitedBeforeShutdown = !ffmpegProcess.isAlive();
                Properties world = new Properties();
                try (var reader = Files.newBufferedReader(boundaries.toPath())) {
                    world.load(reader);
                }
                world.forEach((key, value) -> boundaryEvidence.put(key.toString(), value));
                long stopAfterMs = (long) Math.ceil(Long.parseLong(world.getProperty("worldClearedUs")) / 1000.0
                        + postPadding * 1000 + 2000.0 / frameRate);
                while (System.currentTimeMillis() < stopAfterMs && ffmpegProcess.isAlive()) {
                    sleep(10L);
                }
                // PulseAudio packets can trail wall time; stopping on video readiness alone
                // truncates the game's final audio even when the audio stream is non-empty.
                boundaryEvidence.put("audioCapturedThroughEpochSeconds",
                        waitForAudioCoverage(ffmpegProcess, audioProgress, stopAfterMs / 1000.0));
                stopFfmpeg(ffmpegProcess);
                if (ffmpegProcess.isAlive() || (ffmpegProcess.exitValue() != 0 && ffmpegProcess.exitValue() != 255)) {
                    throw new GradleException("Capture did not finalize successfully before client teardown.");
                }
                Files.writeString(completeSignal.toPath(), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
                boundaryEvidence.put("captureCompleteEpochMs", System.currentTimeMillis());
                getLogger().lifecycle("[CLIENT_GAMETEST_BOUNDARY] CAPTURE_COMPLETE: raw capture finalized before client teardown");
            }

            if (!gradleProcess.waitFor(30, TimeUnit.SECONDS)) {
                throw new GradleException("Client GameTest did not terminate within 30 seconds of capture completion.");
            }
            gradleStatus = gradleProcess.exitValue();
            logThread.join(1000L);
        } catch (RecordingPreflightException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            captureFailure = exception.toString();
            getLogger().error("Client GameTest recording failed; retaining raw evidence.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while recording client GameTest.", exception);
        } finally {
            if (ffmpegProcess != null) {
                if (!recordingStopObserved && !ffmpegProcess.isAlive()) {
                    ffmpegExitedBeforeShutdown = true;
                }
                stopFfmpeg(ffmpegProcess);
                if (!ffmpegProcess.isAlive()) {
                    ffmpegStatus = ffmpegProcess.exitValue();
                }
            }
            if (gradleProcess != null && gradleProcess.isAlive()) {
                List<ProcessHandle> children = gradleProcess.descendants().toList();
                children.forEach(ProcessHandle::destroy);
                gradleProcess.destroy();
                waitForProcess(gradleProcess, 5, TimeUnit.SECONDS);
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if (gradleProcess.isAlive()) {
                    gradleProcess.destroyForcibly();
                    waitForProcess(gradleProcess, 2, TimeUnit.SECONDS);
                }
            }
            if (!audioSinkId.isBlank()) {
                runCommand(List.of("pactl", "unload-module", audioSinkId), Map.of(), true);
            }
            if (xvfbProcess != null && xvfbProcess.isAlive()) {
                xvfbProcess.destroy();
                waitForProcess(xvfbProcess, 2, TimeUnit.SECONDS);
                if (xvfbProcess.isAlive()) {
                    xvfbProcess.destroyForcibly();
                }
            }
            if (display != null) {
                // The display is free for another recording only after this one's capture has stopped.
                display.release();
            }
            deleteIfExists(startSignal.toPath());
            deleteIfExists(windowSignal.toPath());
            deleteIfExists(readySignal.toPath());
            deleteIfExists(stopSignal.toPath());
            deleteIfExists(completeSignal.toPath());
            deleteIfExists(captureClock.toPath());
            deleteIfExists(videoPipe.toPath());
            deleteIfExists(audioPipe.toPath());
            deleteIfExists(alsoftConfig.toPath());
            if (previousMemoryLow != null) {
                restoreMemoryLow(memoryCgroup, previousMemoryLow);
            }
        }

        if (recordingStopObserved && rawCapture.isFile()) {
            try {
                trimToWorldBoundaries(rawCapture, output, boundaryEvidence, prePadding, postPadding, frameRate,
                        gpuDisplay != null && !gameCapture, failOnTornFrames);
            } catch (RuntimeException exception) {
                captureFailure = exception.toString();
                getLogger().error("Could not trim recording; retaining raw boundary evidence.", exception);
            }
        }
        if (output.isFile() && runCommand(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", output.getAbsolutePath()), Map.of(), true).status() == 0) {
            videoSaved = true;
            getLogger().lifecycle("Recording saved: {}", output.getAbsolutePath());
        }
        if (videoSaved) {
            CommandResult audioProbe = runCommand(List.of("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "csv=p=0", output.getAbsolutePath()), Map.of(), true);
            audioSaved = audioProbe.status() == 0 && !audioProbe.output().trim().isBlank();
            CommandResult analysis = runCommand(List.of("ffmpeg", "-v", "info", "-i", output.getAbsolutePath(),
                    "-vn", "-af", "astats=metadata=0:reset=0", "-f", "null", "-"), Map.of(), true);
            audioDecoded = analysis.status() == 0
                    && Pattern.compile("Number of samples: [1-9][0-9]*").matcher(analysis.output()).find();
            audioNonSilent = Pattern.compile("Peak level dB: -?[0-9]").matcher(analysis.output()).find();
            audioSaved = audioSaved && audioDecoded && audioRouteVerified
                    && soundInitialized.get() && !soundFailed.get();
            try {
                Files.writeString(audioAnalysis.toPath(), analysis.output(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        File resolvedRunDir = runDir.exists() ? canonicalFile(runDir) : null;
        if (resolvedRunDir != null) {
            copyDirectory(resolvedRunDir, keptRunDir);
            getLogger().lifecycle("Client GameTest run directory saved: {}", keptRunDir.getAbsolutePath());
        }

        String clientLog = readClientLog(resolvedRunDir);
        glRenderer = ClientGpuCapture.rendererIn(clientLog);
        sdlVideoDriver = ClientGpuCapture.sdlVideoDriverIn(clientLog);

        List<String> recordingFailures = recordingFailureMessages(
                gradleStatus,
                recordingStartObserved,
                recordingStopObserved,
                ffmpegExitedBeforeShutdown,
                ffmpegStatus,
                videoSaved,
                audioSaved,
                resolvedRunDir != null,
                runDir.toString()
        );
        if (captureFailure != null) {
            recordingFailures.add(captureFailure);
        }
        if (!audioRouteVerified || !soundInitialized.get() || soundFailed.get() || !audioDecoded) {
            recordingFailures.add(gameCapture
                    ? "Game audio must render into the recording through an OpenAL loopback device and decode to PCM samples."
                    : "Game audio must initialize successfully on the isolated sink and decode to PCM samples.");
        }
        if (!sdlVideoDriver.isEmpty() && !sdlVideoDriver.equals("x11")) {
            recordingFailures.add("The client used SDL's " + sdlVideoDriver
                    + " video driver: a recording must render on the recorder's own x11 display.");
        }
        if (gpuDisplay != null) {
            String rendererProblem = ClientGpuCapture.rendererFailure(glRenderer, getExpectedRenderer().getOrElse(""));
            if (rendererProblem != null) {
                recordingFailures.add("The recording display " + gpuDisplay + " did not render the recording: "
                        + rendererProblem + ". Record on a host without a display, or ask for the virtual display "
                        + "with an empty " + ClientGpuCapture.DISPLAY_ENV + ".");
            }
        }
        if (boundaryEvidence.containsKey("clockDriftUs")
                && Math.abs(Long.parseLong(boundaryEvidence.get("clockDriftUs").toString())) > 10_000L) {
            recordingFailures.add("Wall clock drift exceeded 10ms during capture; boundary timing requires review.");
        }
        recordingFailures.forEach(getLogger()::error);

        Map<String, Object> metadataModel = new LinkedHashMap<>();
        metadataModel.put("name", recordingName);
        metadataModel.put("profile", recordingProfile);
        metadataModel.put("trace", truthy(recordingTrace));
        metadataModel.put("startedAt", recordingStartedAt);
        metadataModel.put("finishedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        metadataModel.put("gradleStatus", gradleStatus);
        metadataModel.put("captureMode", gpuDisplay == null ? "xvfb" : "x11");
        metadataModel.put("captureSource", gameCapture ? "game" : "x11grab");
        metadataModel.put("glRenderer", glRenderer.isEmpty() ? null : glRenderer);
        metadataModel.put("sdlVideoDriver", sdlVideoDriver.isEmpty() ? null : sdlVideoDriver);
        metadataModel.put("presentedFps", ClientGpuCapture.presentedFps(boundaryEvidence));
        metadataModel.put("host", hostName());
        Map<String, Object> gpuModel = new LinkedHashMap<>();
        gpuModel.put("requestedDisplay", gpuDisplayRequest.isEmpty() ? null : gpuDisplayRequest);
        gpuModel.put("display", gpuDisplay);
        gpuModel.put("fallbackReason", gpuFallback);
        gpuModel.put("active", gpuDisplay != null && !ClientGpuCapture.isSoftwareRenderer(glRenderer));
        metadataModel.put("gpu", gpuModel);
        metadataModel.put("fps", fps);
        metadataModel.put("videoEncoder", videoEncoderName);
        metadataModel.put("clientFrameCap", ClientGameTestOptions.frameCap(gpuDisplay != null, frameRate));
        metadataModel.put("xvfbScreen", xvfbScreen);
        metadataModel.put("video", output.getAbsolutePath());
        metadataModel.put("videoSaved", videoSaved);
        metadataModel.put("recordingStartObserved", recordingStartObserved);
        metadataModel.put("recordingStopObserved", recordingStopObserved);
        metadataModel.put("audioRequested", true);
        metadataModel.put("audioRoute", "virtual");
        metadataModel.put("audioSource", audioSource);
        metadataModel.put("audioSink", audioSinkName);
        metadataModel.put("audioSaved", audioSaved);
        metadataModel.put("audioRouteVerified", audioRouteVerified);
        metadataModel.put("gameSoundInitialized", soundInitialized.get());
        metadataModel.put("gameSoundInitializationFailed", soundFailed.get());
        metadataModel.put("audioDecoded", audioDecoded);
        metadataModel.put("audioNonSilent", audioNonSilent);
        metadataModel.put("audioAnalysis", audioAnalysis.getAbsolutePath());
        metadataModel.put("audioSilencePolicy", "Quiet intervals are permitted; an entirely silent recording requires semantic review, not an automatic sound failure.");
        metadataModel.put("boundaries", boundaryEvidence);
        metadataModel.put("boundaryEvidence", boundaries.getAbsolutePath());
        metadataModel.put("rawCapture", rawCapture.getAbsolutePath());
        metadataModel.put("captureProgress", progress.getAbsolutePath());
        metadataModel.put("audioPacketTimestamps", audioProgress.getAbsolutePath());
        metadataModel.put("prePaddingSeconds", prePadding);
        metadataModel.put("postPaddingSeconds", postPadding);
        metadataModel.put("ffmpegStatus", ffmpegStatus);
        metadataModel.put("recordingDirectory", canonicalFile(recordingDir).getPath());
        metadataModel.put("runDirectory", resolvedRunDir == null ? null : resolvedRunDir.getAbsolutePath());
        metadataModel.put("keptRunDirectory", keptRunDir.exists() ? keptRunDir.getAbsolutePath() : null);
        Map<String, Object> selectors = new LinkedHashMap<>();
        selectors.put("CLIENT_GAMETEST_PROFILE", processEnvironment.get("CLIENT_GAMETEST_PROFILE"));
        selectors.put("CLIENT_GAMETEST_ONLY", processEnvironment.get("CLIENT_GAMETEST_ONLY"));
        selectors.put("CLIENT_GAMETEST_SUITE", processEnvironment.get("CLIENT_GAMETEST_SUITE"));
        metadataModel.put("selectors", selectors);
        metadataModel.put("gradleArgs", List.copyOf(gradleCommand.subList(1, gradleCommand.size())));
        metadataModel.put("recordingFailures", List.copyOf(recordingFailures));

        try {
            Files.writeString(metadata.toPath(), RecordingJson.pretty(metadataModel) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write recording metadata: " + metadata, exception);
        }
        getLogger().lifecycle("Recording metadata saved: {}", metadata.getAbsolutePath());

        if (!recordingFailures.isEmpty()) {
            throw new GradleException(
                    "Client GameTest recording failed: " + String.join(" ", recordingFailures)
            );
        }
    }

    static List<String> recordingFailureMessages(
            int gradleStatus,
            boolean recordingStartObserved,
            boolean recordingStopObserved,
            boolean ffmpegExitedBeforeShutdown,
            Integer ffmpegStatus,
            boolean videoSaved,
            boolean audioSaved,
            boolean runDirectorySaved,
            String runDirectory
    ) {
        List<String> failures = new ArrayList<>();
        if (gradleStatus != 0) {
            failures.add("Client GameTest failed with status " + gradleStatus + ".");
        }
        if (!recordingStartObserved) {
            failures.add("Client GameTest recording-start signal was not observed.");
        }
        if (!recordingStopObserved) {
            failures.add("Client GameTest recording-stop signal was not observed.");
        }
        if (ffmpegExitedBeforeShutdown) {
            failures.add("ffmpeg exited before the client GameTest completed with status " + ffmpegStatus + ".");
        }
        if (!videoSaved) {
            failures.add("Recorded video is missing or invalid.");
        } else if (!audioSaved) {
            failures.add("Recording audio failed decoding, game sound initialization, or isolated-routing verification.");
        }
        if (!runDirectorySaved) {
            failures.add("Client GameTest run directory was not found: " + runDirectory);
        }
        return failures;
    }

    /**
     * The raw capture's video encoder options for {@value #VIDEO_ENCODER_ENV}: {@code libx264} on the CPU (the
     * default), or {@code h264_nvenc} on an NVIDIA card's encoder, which takes x11grab's BGR frames as they are.
     */
    static List<String> videoEncoder(String name) {
        return switch (name) {
            case "libx264" -> List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p");
            case "h264_nvenc" -> List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "constqp", "-qp", "18");
            default -> throw new GradleException(VIDEO_ENCODER_ENV + " must be libx264 or h264_nvenc, not " + name);
        };
    }

    /** The command that encodes one frame with the capture's encoder options, run before the client starts. */
    static List<String> encoderProbeCommand(List<String> encoder) {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=256x256", "-frames:v", "1"));
        command.addAll(encoder);
        command.addAll(List.of("-f", "null", "-"));
        return command;
    }

    /** Why the capture cannot encode with {@code encoder}, from the probe's exit status and output, or null. */
    static String encoderProbeFailure(String encoder, int status, String output) {
        if (status == 0) {
            return null;
        }
        return "ffmpeg cannot encode with " + encoder + " on this host, so the client was not started ("
                + VIDEO_ENCODER_ENV + "=" + encoder + "): " + output.strip()
                + (encoder.equals("libx264") ? "" : ". Unset " + VIDEO_ENCODER_ENV + " to encode on the CPU with libx264.");
    }

    /**
     * Why a capture at {@code frameRate} cannot run on the display it got, or null. Above the default rate the
     * client needs the card: on the recorder's own Xvfb its frame cap stays at the default (see
     * {@link ClientGameTestOptions#frameCap}), so every other captured frame would repeat the one before.
     */
    static String captureRateFailure(int frameRate, String gpuDisplay, String fallbackReason) {
        if (frameRate <= ClientGameTestOptions.DEFAULT_CAPTURE_FPS || gpuDisplay != null) {
            return null;
        }
        return ClientGameTestOptions.CAPTURE_FPS_ENV + "=" + frameRate + " needs a recording display on the card, "
                + "and this recording got the virtual display"
                + (fallbackReason == null ? "" : " (" + fallbackReason + ")")
                + ", where the client presents at most " + ClientGameTestOptions.DEFAULT_CAPTURE_FPS
                + " frames a second. The client was not started.";
    }

    /**
     * The torn frames of the kept range {@code [firstKept, endKept)} of the raw capture, by frame number of the
     * saved video. Only a take on a card display is checked: there x11grab's copy does not wait for the client's
     * present (see {@link TornFrames}). On the recorder's own Xvfb the check found one frame in 19 takes, and that
     * frame was a genuine image, so {@code rawTornFrames} is not run there.
     */
    static List<Integer> tornFramesToReplace(boolean cardDisplay, long firstKept, long endKept,
            Supplier<List<Integer>> rawTornFrames) {
        if (!cardDisplay) {
            return List.of();
        }
        return rawTornFrames.get().stream()
                .filter(frame -> frame >= firstKept && frame < endKept)
                .map(frame -> (int) (frame - firstKept))
                .toList();
    }

    /** Whether a torn frame left after the repair fails the recording: {@value #TEAR_CHECK_ENV} is fail or warn. */
    static boolean failsOnTornFrames(String mode) {
        return switch (mode) {
            case "fail" -> true;
            case "warn" -> false;
            default -> throw new GradleException(TEAR_CHECK_ENV + " must be fail or warn, not " + mode);
        };
    }

    /** Whether {@value #CAPTURE_SOURCE_ENV} asks for the game's own picture and sound, or for a screen grab. */
    static boolean capturesGame(String source) {
        return switch (source) {
            case "game" -> true;
            case "x11grab" -> false;
            default -> throw new GradleException(CAPTURE_SOURCE_ENV + " must be game or x11grab, not " + source);
        };
    }

    /**
     * Writes {@code value} to {@code path} so a reader sees either nothing or the whole text. The client polls the
     * capture clock every 50 ms and parses what it reads, so a length-0 or part-written file would be a clock it
     * cannot use; a move into place is the same handover the client's own signal writer uses
     * ({@code ClientGameTestRecordingSession.java:250-263}).
     */
    static void writeAtomically(Path path, String value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, value, StandardCharsets.UTF_8);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The game capture's first frame, as a count of capture frames since the epoch: the next whole frame after
     * {@code nowUs}, so frame {@code k} of the capture is at exactly {@code (start + k) / fps} seconds.
     */
    static long captureStartFrames(long nowUs, int frameRate) {
        return Math.ceilDiv(nowUs * frameRate, 1_000_000L);
    }

    /**
     * The ffmpeg command for the game's own picture and sound: the client writes raw RGBA frames (the main target is
     * {@code RGBA8_UNORM}, {@code MainTarget.java:18}, and its readback comes bottom row first, which vanilla's
     * screenshot flips too, {@code Screenshot.java:102} in the 26.3 sources) on the capture clock to {@code videoPipe},
     * and 48 kHz 16-bit stereo to {@code audioPipe}, both starting at frame {@code startFrames} of the capture rate.
     * The two streams' timestamps are shifted to the epoch clock in whole frames and whole samples, as a screen
     * grab's are with {@code -copyts}, so the trimming and the audio checks work the same on both.
     * ({@code -itsoffset} leaves raw PCM at zero in ffmpeg 8.1.)
     */
    static List<String> gameCaptureCommand(int frameRate, String videoSize, long startFrames, String videoPipe,
            String audioPipe, String progress, String audioProgress, String rawCapture, List<String> encoder) {
        long startSamples = Math.round(startFrames * (double) GAME_SOUND_RATE / frameRate);
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-copyts", "-stats_period", "0.05", "-progress", progress,
                "-f", "rawvideo", "-pix_fmt", "rgba", "-video_size", videoSize,
                "-framerate", Integer.toString(frameRate), "-thread_queue_size",
                Integer.toString(VIDEO_QUEUE_PACKETS), "-i", videoPipe,
                "-f", "s16le", "-ar", Integer.toString(GAME_SOUND_RATE), "-ac", "2",
                "-thread_queue_size", "1024", "-i", audioPipe,
                "-map", "0:v:0", "-map", "1:a:0",
                "-vf", "vflip,setpts=PTS+" + startFrames, "-af", "asetpts=PTS+" + startSamples));
        command.addAll(encoder);
        command.addAll(List.of("-fps_mode", "passthrough", "-enc_time_base:v", "demux", "-c:a", "pcm_s16le",
                "-stats_mux_pre:a:0", audioProgress,
                "-stats_mux_pre_fmt:a:0", "{pts} {tb}",
                "-thread_queue_size", Integer.toString(MUX_QUEUE_PACKETS), rawCapture));
        return command;
    }

    static List<String> captureCommand(String fps, String videoSize, String display, String audioSource,
            String progress, String audioProgress, String rawCapture, int offsetX, int offsetY, List<String> encoder) {
        // The grab starts at the client's window, not at the display's origin: vanilla centres the window.
        String input = display + ".0+" + offsetX + "," + offsetY;
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-copyts", "-stats_period", "0.05", "-progress", progress,
                "-f", "x11grab", "-framerate", fps, "-video_size", videoSize, "-draw_mouse", "0", "-i", input,
                "-f", "pulse", "-thread_queue_size", "1024", "-i", audioSource,
                "-map", "0:v:0", "-map", "1:a:0"));
        command.addAll(encoder);
        // Passthrough alone still rounds timestamps into the encoder's default 1/fps timebase.
        command.addAll(List.of("-fps_mode", "passthrough", "-enc_time_base:v", "demux", "-c:a", "pcm_s16le",
                "-stats_mux_pre:a:0", audioProgress,
                "-stats_mux_pre_fmt:a:0", "{pts} {tb}",
                "-thread_queue_size", Integer.toString(MUX_QUEUE_PACKETS), rawCapture));
        return command;
    }

    static Path ownCgroup(Path procSelfCgroup, Path cgroupRoot) {
        try {
            for (String line : Files.readAllLines(procSelfCgroup, StandardCharsets.UTF_8)) {
                if (line.startsWith("0::/")) {
                    return cgroupRoot.resolve(line.substring(4));
                }
            }
        } catch (IOException exception) {
            return null;
        }
        return null;
    }

    /**
     * When the job runs in a cgroup of its own with a memory limit (a job runner's slot), asks the kernel to
     * reclaim from sibling jobs first by setting {@code memory.low} to that limit. When the parent hits its
     * limit it otherwise evicts the capture's own libraries and the game's files from the page cache, and
     * every thread of ffmpeg then waits on a busy disk to read them back: x11grab stops for seconds. A cgroup
     * without a limit may be shared with unrelated processes and is left alone.
     *
     * @return the previous {@code memory.low} to restore, or null when nothing was changed
     */
    static String protectMemory(Path cgroup) {
        try {
            String limit = Files.readString(cgroup.resolve("memory.max"), StandardCharsets.UTF_8).strip();
            if (!limit.matches("[0-9]+")) {
                return null;
            }
            Path low = cgroup.resolve("memory.low");
            String previous = Files.readString(low, StandardCharsets.UTF_8).strip();
            Files.writeString(low, limit, StandardCharsets.UTF_8);
            return previous;
        } catch (IOException exception) {
            return null;
        }
    }

    static void restoreMemoryLow(Path cgroup, String previous) {
        try {
            Files.writeString(cgroup.resolve("memory.low"), previous, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // The job's cgroup is gone with its last process; there is nothing left to restore.
        }
    }

    /**
     * The display this recording captures, and the process that serves it when the recorder started
     * one. A display named by {@code GTR_RECORDING_DISPLAY} is used as it is: the machine's card is
     * behind its GLX, which is what makes the client present as fast as the card does. A named display
     * that does not answer falls back to the recorder's own virtual one, so an unattended run on a host
     * without a card still records.
     */
    private DisplayResolution startDisplay(String requested, String configuredDisplay, String configuredScreen) {
        String gpuDisplay = ClientGpuCapture.display(requested);
        String fallback = null;
        if (gpuDisplay != null) {
            ExclusiveDisplayLock lock = null;
            try {
                lock = ExclusiveDisplayLock.tryAcquire(gpuDisplay);
            } catch (UncheckedIOException exception) {
                fallback = exception.getMessage();
            }
            if (lock == null) {
                if (fallback == null) {
                    fallback = "X display " + gpuDisplay + " is recording for another job (its lock "
                            + ExclusiveDisplayLock.lockFile(gpuDisplay) + " is held)";
                }
                getLogger().lifecycle(
                        "Recording display {} is taken; recording on the recorder's own virtual display. {}",
                        gpuDisplay, fallback);
            } else {
                CommandResult info = runCommand(List.of("xdpyinfo", "-display", gpuDisplay), Map.of(), true);
                String screen = ClientGpuCapture.screen(info.output());
                if (info.status() == 0 && !screen.isEmpty()) {
                    // One overlay standard: the window and the capture stay the standard size, whatever
                    // the card display's own size is, and a display too small to hold it is an error.
                    String standard = ClientGameTestOptions.DEFAULT_RECORDER_SCREEN;
                    if (!ClientGpuCapture.fits(screen, standard)) {
                        lock.close();
                        throw new GradleException("The recording display " + gpuDisplay + " is " + screen
                                + ", too small to record at the standard " + standard + " window and capture. Point "
                                + ClientGpuCapture.DISPLAY_ENV + " at a display of at least that size, or unset it to"
                                + " record on the recorder's own virtual display.");
                    }
                    getLogger().lifecycle("Recording X display {} ({}), which renders with the machine's graphics card.",
                            gpuDisplay, screen);
                    getLogger().lifecycle("The client's window is visible on that display while it runs; it is the"
                            + " standard {}, and the capture starts at the window once it shows.", standard);
                    return new DisplayResolution(gpuDisplay, standard, screen, null, gpuDisplay, null, lock);
                }
                lock.close();
                fallback = "X display " + gpuDisplay + " does not answer: "
                        + info.output().lines().findFirst().orElse("");
                getLogger().warn(
                        "The recording display {} was ignored; recording on the recorder's own virtual display. {}",
                        requested, fallback);
            }
        }
        String display = configuredDisplay;
        if (display.isBlank()) {
            display = findFreeXvfbDisplay();
        }
        if (!display.startsWith(":")) {
            display = ":" + display;
        }
        String displayNumber = display.substring(1);
        if (!displayNumber.matches("[0-9]+")
                || new File("/tmp/.X" + displayNumber + "-lock").exists()
                || new File("/tmp/.X11-unix/X" + displayNumber).exists()) {
            throw new GradleException("Refusing to attach to an existing or invalid X display: " + display);
        }
        getLogger().lifecycle("Starting virtual X display {} ({})...", display, configuredScreen);
        Process xvfb = null;
        try {
            xvfb = new ProcessBuilder("Xvfb", display, "-screen", "0", configuredScreen, "-nolisten", "tcp")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to start Xvfb: " + display, exception);
        }
        try {
            waitForXDisplay(display, xvfb);
        } catch (RuntimeException exception) {
            // A display that never answered must not keep running and hold its number and lock file.
            xvfb.destroy();
            waitForProcess(xvfb, 2, TimeUnit.SECONDS);
            if (xvfb.isAlive()) {
                xvfb.destroyForcibly();
            }
            throw exception;
        }
        return new DisplayResolution(display, configuredScreen, configuredScreen, xvfb, null, fallback, null);
    }

    /**
     * Where x11grab starts for the client's window: the window's own origin and size, reported by the
     * recording helper from inside the client and read from the window signal. Vanilla centres a
     * windowed client on its monitor, so the display's origin is not the window's.
     */
    private int[] windowGrabOffset(File windowSignal, String display, String displayScreen, String videoSize) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && (!windowSignal.isFile() || windowSignal.length() == 0)) {
            sleep(50L);
        }
        String reported;
        try {
            reported = windowSignal.isFile()
                    ? Files.readString(windowSignal.toPath(), StandardCharsets.UTF_8)
                    : "";
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the client's window position " + windowSignal, exception);
        }
        try {
            return ClientGpuCapture.grabOffset(displayScreen, videoSize, ClientGpuCapture.windowRect(reported));
        } catch (IllegalArgumentException exception) {
            throw new GradleException(exception.getMessage() + " The client reported '" + reported.strip()
                    + "' as x y width height on display " + display + ".");
        }
    }

    /** The start of the client's own log, where it names the graphics device and the SDL video driver. */
    private static String readClientLog(File runDirectory) {
        if (runDirectory == null) {
            return "";
        }
        File log = new File(runDirectory, "logs/latest.log");
        if (!log.isFile()) {
            return "";
        }
        try (var reader = new InputStreamReader(new java.io.FileInputStream(log), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2 * 1024 * 1024];
            int read = reader.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read);
        } catch (IOException exception) {
            return "";
        }
    }

    private static String hostName() {
        try {
            return Files.readString(Path.of("/etc/hostname"), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return env("HOSTNAME", "");
        }
    }

    private void requireTools(Collection<String> names) {
        names.forEach(this::requireTool);
    }

    private void requireTool(String name) {
        CommandResult result = runCommand(List.of("sh", "-c", "command -v " + name), Map.of(), true);
        if (result.status() != 0) {
            throw new GradleException("Missing required tool: " + name);
        }
    }

    private CommandResult runCommand(List<String> command) {
        return runCommand(command, Map.of(), false);
    }

    private CommandResult runCommand(List<String> command, Map<String, String> environment, boolean allowFailure) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(getProjectDirectory().get().getAsFile());
            builder.redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != 0 && !allowFailure) {
                throw new GradleException("Command failed (" + status + "): " + String.join(" ", command)
                        + System.lineSeparator() + output);
            }
            return new CommandResult(status, output);
        } catch (IOException exception) {
            throw new UncheckedIOException("Command failed to start: " + String.join(" ", command), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while running command: " + String.join(" ", command), exception);
        }
    }

    private String findFreeXvfbDisplay() {
        for (int number = 90; number <= 130; number++) {
            if (!new File("/tmp/.X" + number + "-lock").exists() && !new File("/tmp/.X11-unix/X" + number).exists()) {
                return Integer.toString(number);
            }
        }
        throw new GradleException("Expected to find a free X display between :90 and :130.");
    }

    private void waitForXDisplay(String displayName, Process xvfbProcess) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!xvfbProcess.isAlive()) {
                throw new GradleException("Xvfb exited before becoming ready.");
            }
            CommandResult check = runCommand(List.of("xdpyinfo", "-display", displayName), Map.of(), true);
            if (check.status() == 0) {
                return;
            }
            sleep(100L);
        }
        throw new GradleException("Timed out waiting for X display " + displayName + ".");
    }

    private String createVirtualAudioSink(String sinkName) {
        boolean exists = runCommand(List.of("pactl", "list", "short", "sinks")).output().lines()
                .map(line -> line.split("\\s+"))
                .anyMatch(columns -> columns.length > 1 && columns[1].equals(sinkName));
        if (exists) {
            throw new GradleException("Refusing to reuse an existing audio sink: " + sinkName);
        }
        // Use the same PulseAudio server as Minecraft, ffmpeg, and route verification.
        // Direct pw-cli calls would ignore PULSE_SERVER and touch another session.
        return runCommand(List.of(
                "pactl", "load-module", "module-null-sink",
                "sink_name=" + sinkName, "rate=48000", "channels=2",
                "channel_map=front-left,front-right",
                "sink_properties=device.description=" + sinkName
        )).output().strip();
    }

    private void verifyGameAudioRoute(String sinkName, String applicationId) {
        String sinkIndex = "";
        for (String line : runCommand(List.of("pactl", "list", "short", "sinks")).output().lines().toList()) {
            String[] columns = line.split("\\s+");
            if (columns.length > 1 && columns[1].equals(sinkName)) {
                sinkIndex = columns[0];
            }
        }
        if (sinkIndex.isBlank()) {
            throw new GradleException("Dedicated sink is not visible to PulseAudio: " + sinkName);
        }
        boolean monitorFound = runCommand(List.of("pactl", "list", "short", "sources")).output().lines()
                .map(line -> line.split("\\s+"))
                .anyMatch(columns -> columns.length > 1 && columns[1].equals(sinkName + ".monitor"));
        if (!monitorFound) {
            throw new GradleException("Dedicated sink monitor is not available.");
        }
        String inputs = runCommand(List.of("pactl", "list", "sink-inputs")).output();
        for (String input : inputs.split("Sink Input #")) {
            if (input.contains("application.id = \"" + applicationId + "\"")) {
                var route = Pattern.compile("(?m)^\\s*Sink:\\s*(\\d+)\\s*$").matcher(input);
                if (!route.find() || !sinkIndex.equals(route.group(1))) {
                    throw new GradleException("Minecraft audio is not routed exclusively to the dedicated sink.");
                }
                getLogger().lifecycle("Verified Minecraft PulseAudio stream on sink {} (index {}).", sinkName, sinkIndex);
                return;
            }
        }
        throw new GradleException("Minecraft did not open a tagged PulseAudio playback stream. Check OpenAL initialization.");
    }

    private void waitForCaptureFrames(Process ffmpeg, File progress) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && ffmpeg.isAlive()) {
            if (progress.isFile() && Pattern.compile("(?m)^frame=[1-9][0-9]*\\s*$")
                    .matcher(Files.readString(progress.toPath())).find()) {
                return;
            }
            sleep(25L);
        }
        throw new GradleException("ffmpeg did not report an encoded video frame before world entry.");
    }

    private double waitForAudioCoverage(Process ffmpeg, File timestamps, double through) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!timestamps.isFile() && System.nanoTime() < deadline && ffmpeg.isAlive()) {
            sleep(25L);
        }
        if (timestamps.isFile()) {
            try (var reader = Files.newBufferedReader(timestamps.toPath())) {
                while (System.nanoTime() < deadline && ffmpeg.isAlive()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] fields = line.split("[ /]");
                        if (fields.length != 3) continue;
                        double seconds = Double.parseDouble(fields[0])
                                * Long.parseLong(fields[1]) / Long.parseLong(fields[2]);
                        if (seconds >= through) return seconds;
                    }
                    sleep(25L);
                }
            }
        }
        throw new GradleException("Audio packets did not cover the required capture boundary: " + through);
    }

    private void trimToWorldBoundaries(File raw, File output, Map<String, Object> evidence,
            double prePadding, double postPadding, int fps, boolean cardDisplay, boolean failOnTornFrames) {
        double firstTick = Long.parseLong(evidence.get("firstWorldTickStartUs").toString()) / 1_000_000.0;
        double lastTick = Long.parseLong(evidence.get("lastWorldTickEndUs").toString()) / 1_000_000.0;
        double requestedStart = firstTick - prePadding;
        double worldExit = Long.parseLong(evidence.get("worldClearedUs").toString()) / 1_000_000.0;
        double requestedEnd = (postPadding == 0 ? lastTick : Math.max(lastTick, worldExit)) + postPadding;
        CommandResult probe = runCommand(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_frames", "-show_entries", "frame=best_effort_timestamp_time", "-of", "csv=p=0", raw.getAbsolutePath()));
        double selectedStart = Double.NaN;
        double selectedEnd = Double.NaN;
        double previous = Double.NaN;
        double largestGap = 0;
        double firstTickFrame = Double.NaN;
        double beforeFirstTickFrame = Double.NaN;
        double lastTickFrame = Double.NaN;
        double afterLastTickFrame = Double.NaN;
        long frameIndex = 0;
        long startIndex = -1;
        long endIndex = -1;
        for (var lines = probe.output().lines().iterator(); lines.hasNext();) {
            String line = lines.next();
            int comma = line.indexOf(',');
            String timestamp = (comma < 0 ? line : line.substring(0, comma)).strip();
            if (!timestamp.matches("[0-9]+(?:\\.[0-9]+)?")) {
                continue;
            }
            double pts = Double.parseDouble(timestamp);
            if (pts <= firstTick) {
                beforeFirstTickFrame = firstTickFrame;
                firstTickFrame = pts;
            }
            if (pts <= lastTick && pts < requestedEnd) {
                lastTickFrame = pts;
            }
            if (pts > lastTick && pts < requestedEnd && Double.isNaN(afterLastTickFrame)) {
                afterLastTickFrame = pts;
            }
            if (!Double.isNaN(previous)) {
                largestGap = Math.max(largestGap, pts - previous);
            }
            previous = pts;
            if (pts <= requestedStart) {
                selectedStart = pts;
                startIndex = frameIndex;
            }
            if (pts >= requestedEnd && Double.isNaN(selectedEnd)) {
                selectedEnd = pts;
                endIndex = frameIndex;
            }
            frameIndex++;
        }
        if (Double.isNaN(selectedStart) || Double.isNaN(selectedEnd) || selectedEnd <= selectedStart) {
            throw new GradleException("Raw capture does not cover requested actual-world boundaries and padding.");
        }
        File frameTimes = new File(output.getParentFile(), output.getName() + "-raw-frame-times.csv");
        try {
            Files.writeString(frameTimes.toPath(), probe.output(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        String start = String.format(Locale.ROOT, "%.6f", selectedStart);
        String end = String.format(Locale.ROOT, "%.6f", selectedEnd);
        List<Integer> torn = tornFramesToReplace(cardDisplay, startIndex, endIndex, () -> tornFrames(raw));
        if (!torn.isEmpty()) {
            getLogger().lifecycle("Replacing {} torn frame(s) with the frame before each: {}", torn.size(), torn);
        }
        // Reserve 6 dB of AAC reconstruction headroom independently of game-mix peaks.
        runCommand(List.of("ffmpeg", "-y", "-copyts", "-i", raw.getAbsolutePath(),
                "-filter_complex", "[0:v]trim=start_frame=" + startIndex + ":end_frame=" + endIndex
                        + ",setpts=PTS-STARTPTS[trimmed];" + TornFrames.repairFilter("trimmed", "video", torn),
                "-map", "[video]", "-map", "0:a:0",
                "-af", "atrim=start=" + start + ":end=" + end + ",asetpts=PTS-" + start + "/TB,volume=0.5",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-fps_mode", "passthrough", "-enc_time_base:v", "demux", "-c:a", "aac", "-b:a", "160k",
                "-movflags", "+faststart", output.getAbsolutePath()));
        if (cardDisplay) {
            evidence.put("tornFramesReplaced", torn);
            List<Integer> left = tornFrames(output);
            if (!left.isEmpty()) {
                evidence.put("tornFramesLeft", left);
                String message = "The recording still has torn frames after the repair: " + left;
                if (failOnTornFrames) {
                    throw new GradleException(message + ". " + TEAR_CHECK_ENV + "=warn keeps such a take.");
                }
                getLogger().warn("{} ({}=warn keeps the take).", message, TEAR_CHECK_ENV);
            }
        }
        evidence.put("requestedStartEpochSeconds", requestedStart);
        evidence.put("requestedEndEpochSeconds", requestedEnd);
        evidence.put("selectedStartEpochSeconds", selectedStart);
        evidence.put("selectedEndExclusiveEpochSeconds", selectedEnd);
        evidence.put("rawStartFrameIndex", startIndex);
        evidence.put("rawEndFrameIndexExclusive", endIndex);
        evidence.put("firstWorldTickVideoSeconds", firstTick - selectedStart);
        evidence.put("lastWorldTickVideoSeconds", lastTick - selectedStart);
        evidence.put("worldExitVideoSeconds", worldExit - selectedStart);
        evidence.put("effectivePostWorldExitPaddingSeconds", selectedEnd - worldExit);
        evidence.put("effectivePrePaddingSeconds", firstTick - selectedStart);
        evidence.put("effectivePostPaddingSeconds", selectedEnd - lastTick);
        evidence.put("nominalFrameIntervalSeconds", 1.0 / fps);
        evidence.put("largestObservedRawFrameGapSeconds", largestGap);
        evidence.put("rawFrameTimestamps", frameTimes.getAbsolutePath());
        evidence.put("boundaryPolicy", "Outward quantization to actual captured frame PTS. Start: first tick minus pre-padding. With positive post-padding: observed world clear plus padding, retaining all intervening presentations. With zero post-padding: last tick completion. End is exclusive at/after the requested bound. Zero padding includes intersecting frame intervals, not exact tick-aligned frames. GPU present call brackets are CPU timestamps, not GPU completion or X11 sampling timestamps.");
        Map<String, Double> boundaryFrames = Map.of(
                "first-world-tick", Math.max(0, firstTickFrame - selectedStart),
                "last-world-tick", Math.max(0, lastTickFrame - selectedStart),
                "before-first-world-tick", Double.isNaN(beforeFirstTickFrame) ? 0 : Math.max(0, beforeFirstTickFrame - selectedStart),
                "after-last-world-tick", Math.max(0, (Double.isNaN(afterLastTickFrame) ? lastTickFrame : afterLastTickFrame) - selectedStart));
        evidence.put("boundaryFrameVideoSeconds", boundaryFrames);
        evidence.put("boundaryFramePolicy", "First/last images use captured frames at or before each tick boundary. Adjacent images clamp to retained video when padding excludes the neighboring frame; inspect their actual PTS rather than assuming they lie outside the tick interval.");
        Map<String, File> boundaryImages = new LinkedHashMap<>();
        boundaryFrames.forEach((marker, seconds) -> boundaryImages.put(marker, saveBoundaryFrame(output, marker, seconds)));
        requireChangingPicture(output, selectedEnd - selectedStart, boundaryImages);
        getLogger().lifecycle("[CLIENT_GAMETEST_BOUNDARY] VIDEO_WINDOW pre={}s post={}s; raw frame interval [{}, {})",
                firstTick - selectedStart, selectedEnd - lastTick, startIndex, endIndex);
    }

    /** The torn frames of a video, by frame number; see {@link TornFrames}. */
    private List<Integer> tornFrames(File video) {
        ProcessBuilder builder = new ProcessBuilder(TornFrames.maskCommand(video.getAbsolutePath()));
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process ffmpeg = builder.start();
            List<Integer> torn = TornFrames.find(ffmpeg.getInputStream());
            if (ffmpeg.waitFor() != 0) {
                throw new GradleException("ffmpeg could not compare the frames of " + video.getName());
            }
            return torn;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while comparing the frames of " + video.getName(), exception);
        }
    }

    /**
     * Fails a capture whose images never change. A client whose frames never reach the display, such
     * as one whose OpenGL backend cannot drive the faker library of a GPU recording, otherwise records
     * a black still and reports nothing wrong.
     *
     * <p>The four boundary images alone can all fall inside a stretch of the film that stands still,
     * such as a caption that does not change between two world ticks, so the check also decodes a frame
     * at each of {@value #PICTURE_SAMPLES} evenly spaced points of the recorded window and fails only
     * when every one of them is identical.
     */
    private void requireChangingPicture(File video, double seconds, Map<String, File> boundaryImages) {
        if (seconds < 3 || boundaryImages.size() < 2) {
            return;
        }
        List<File> samples = new ArrayList<>();
        try {
            for (int sample = 1; sample <= PICTURE_SAMPLES; sample++) {
                samples.add(saveBoundaryFrame(video, "sample-" + sample, seconds * sample / (PICTURE_SAMPLES + 1.0)));
            }
            Set<Integer> pictures = new HashSet<>();
            List<File> frames = new ArrayList<>(boundaryImages.values());
            frames.addAll(samples);
            for (File frame : frames) {
                try {
                    pictures.add(Arrays.hashCode(Files.readAllBytes(frame.toPath())));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                if (pictures.size() > 1) {
                    return;
                }
            }
            throw new GradleException("All " + frames.size() + " decoded frames (" + boundaryImages.size()
                    + " boundaries and " + samples.size() + " samples) are identical: the client's pictures never"
                    + " reached the recording display. " + video.getName());
        } finally {
            samples.forEach(frame -> deleteIfExists(frame.toPath()));
        }
    }

    private File saveBoundaryFrame(File video, String marker, double seconds) {
        File frame = new File(video.getParentFile(), video.getName() + "-" + marker + ".png");
        runCommand(List.of("ffmpeg", "-y", "-v", "error", "-ss", String.format(Locale.ROOT, "%.6f", Math.max(0, seconds)),
                "-i", video.getAbsolutePath(), "-frames:v", "1", frame.getAbsolutePath()));
        if (!frame.isFile() || frame.length() == 0) {
            throw new GradleException("No decoded frame for recording boundary " + marker + " at " + seconds + "s.");
        }
        return frame;
    }

    private static double parsePadding(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 60) {
                throw new NumberFormatException("outside 0..60 seconds");
            }
            return seconds;
        } catch (NumberFormatException exception) {
            throw new GradleException("Recording padding must be a finite number from 0 to 60 seconds: " + value, exception);
        }
    }

    private void copyDirectory(File source, File target) {
        if (target.exists()) {
            throw new GradleException("Refusing to overwrite existing kept run directory: " + target);
        }
        try (Stream<Path> sourcePaths = Files.walk(source.toPath())) {
            sourcePaths.forEach(sourcePath -> {
                Path relative = source.toPath().relativize(sourcePath);
                Path targetPath = target.toPath().resolve(relative);
                try {
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to copy client GameTest run directory from " + source + " to " + target, exception);
        }
    }

    private Thread streamToStdout(Process process, String name, java.util.function.Consumer<String> observer) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    observer.accept(line);
                }
            } catch (IOException exception) {
                getLogger().debug("Stopped reading process output for {}.", name, exception);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static File projectFile(File projectDir, String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(projectDir, path);
    }

    private static int parsePositiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new GradleException("Expected " + name + " to be a positive integer, got: " + value, exception);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean truthy(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return List.of("1", "true", "yes", "on").contains(normalized);
    }

    private static String sanitizePathComponent(String value) {
        String sanitized = (value == null ? "client-gametest" : value).replaceAll("[^A-Za-z0-9._-]", "-");
        while (sanitized.contains("--")) {
            sanitized = sanitized.replace("--", "-");
        }
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        return sanitized.isBlank() ? "client-gametest" : sanitized;
    }

    private static String videoSizeFromXvfbScreen(String xvfbScreen) {
        String[] parts = xvfbScreen.split("x");
        if (parts.length < 2) {
            throw new GradleException("Expected GTR_RECORDING_XVFB_SCREEN to look like WIDTHxHEIGHTxDEPTH, got: " + xvfbScreen);
        }
        return parts[0] + "x" + parts[1];
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while waiting for recording process.", exception);
        }
    }

    private void stopFfmpeg(Process ffmpegProcess) {
        if (!ffmpegProcess.isAlive()) {
            return;
        }
        runCommand(List.of("kill", "-INT", Long.toString(ffmpegProcess.pid())), Map.of(), true);
        waitForProcess(ffmpegProcess, 10, TimeUnit.SECONDS);
        if (ffmpegProcess.isAlive()) {
            ffmpegProcess.destroyForcibly();
            waitForProcess(ffmpegProcess, 2, TimeUnit.SECONDS);
        }
    }

    private static void waitForProcess(Process process, long timeout, TimeUnit unit) {
        try {
            process.waitFor(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while waiting for process to exit.", exception);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to delete temporary recording file: " + path, exception);
        }
    }

    private static File canonicalFile(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to resolve canonical file: " + file, exception);
        }
    }

    private record CommandResult(int status, String output) {
    }

    /** A check that stops the recording before the client starts, failing the task with only its own message. */
    private static final class RecordingPreflightException extends GradleException {
        private RecordingPreflightException(String message) {
            super(message);
        }
    }

    /**
     * The display a recording captures, the size of the client's window and of the capture, the display's
     * own size, the process serving it when the recorder started one, the display it was asked to use,
     * and why that one was not used.
     */
    private record DisplayResolution(String display, String screen, String displayScreen, Process process,
            String gpuDisplay, String fallbackReason, ExclusiveDisplayLock lock) {
        /** Releases the display's claim, if this recording took it. */
        private void release() {
            if (lock != null) {
                lock.close();
            }
        }
    }
}
