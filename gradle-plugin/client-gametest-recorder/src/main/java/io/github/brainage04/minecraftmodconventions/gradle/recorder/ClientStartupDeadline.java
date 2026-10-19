package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Bounds how long an automated client launch may take to finish loading. The launched client gets the system
 * property {@value #READY_FILE_PROPERTY}; the runtime helper writes that file once the client has finished loading
 * and its GameTests can start. If the file does not appear in time, every process the launch started (the client,
 * and {@code xvfb-run} with its {@code Xvfb}) is stopped and the task fails with a message saying so.
 */
public final class ClientStartupDeadline {
    /** System property naming the file the runtime helper writes once the client has finished loading. */
    public static final String READY_FILE_PROPERTY = "minecraftmodconventions.clientReadyFile";
    /** Gradle property overriding the deadline, in seconds. */
    public static final String TIMEOUT_PROPERTY = "minecraftmodconventions.clientStartupTimeoutSeconds";
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final long POLL_MILLIS = 200L;
    private static final long TERMINATE_GRACE_SECONDS = 10L;

    private final String taskPath;
    private final Path readyFile;
    private final Duration timeout;
    private final Logger logger;
    private final Thread thread;
    private final Object lock = new Object();
    private boolean closed;
    private boolean stopping;
    private volatile boolean expired;
    private volatile List<String> stopped = List.of();

    private ClientStartupDeadline(String taskPath, Path readyFile, Duration timeout, Logger logger) {
        this.taskPath = taskPath;
        this.readyFile = readyFile;
        this.timeout = timeout;
        this.logger = logger;
        this.thread = new Thread(this::watch, "client startup deadline " + taskPath);
        this.thread.setDaemon(true);
    }

    /**
     * Makes {@code task}, which launches one client, pass {@code readyFile} to it through {@code addJvmArg} and fail
     * once the client misses its deadline. Every action the task has at this point runs inside the deadline.
     */
    public static void install(Task task, Path readyFile, Provider<Integer> timeoutSeconds, Action<String> addJvmArg) {
        addJvmArg.execute(jvmArgument(readyFile));
        List<Action<? super Task>> launch = new ArrayList<>(task.getActions());
        task.getActions().clear();
        task.getActions().add(running -> {
            ClientStartupDeadline deadline = start(
                    running.getPath(),
                    readyFile,
                    Duration.ofSeconds(positive(timeoutSeconds.get())),
                    running.getLogger()
            );
            RuntimeException launchFailure = null;
            try {
                launch.forEach(action -> action.execute(running));
            } catch (RuntimeException exception) {
                launchFailure = exception;
            }
            deadline.close();
            if (deadline.expired()) {
                throw deadline.failure(launchFailure);
            }
            if (launchFailure != null) {
                throw launchFailure;
            }
        });
    }

    static String jvmArgument(Path readyFile) {
        return "-D" + READY_FILE_PROPERTY + "=" + readyFile.toAbsolutePath();
    }

    static ClientStartupDeadline start(String taskPath, Path readyFile, Duration timeout, Logger logger) {
        try {
            Files.deleteIfExists(readyFile);
            Files.createDirectories(readyFile.toAbsolutePath().getParent());
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not reset " + readyFile, exception);
        }
        ClientStartupDeadline deadline = new ClientStartupDeadline(taskPath, readyFile, timeout, logger);
        deadline.thread.start();
        return deadline;
    }

    boolean expired() {
        return expired;
    }

    /** Ends the watch; if the deadline has already expired, waits until the launch's processes are stopped. */
    void close() {
        synchronized (lock) {
            closed = true;
            if (!stopping) {
                thread.interrupt();
            }
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(3 * TERMINATE_GRACE_SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    GradleException failure(Throwable cause) {
        return new GradleException(message(), cause);
    }

    private String message() {
        return taskPath + ": the client did not finish loading within " + timeout.toSeconds() + " s (set "
                + TIMEOUT_PROPERTY + " to change the deadline). Stopped the processes it started: "
                + (stopped.isEmpty() ? "none were still running" : String.join("; ", stopped)) + ".";
    }

    private void watch() {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!Files.exists(readyFile)) {
                if (System.nanoTime() - deadline >= 0) {
                    synchronized (lock) {
                        if (closed) {
                            return;
                        }
                        stopping = true;
                    }
                    expired = true;
                    stopped = stopLaunch(jvmArgument(readyFile));
                    logger.error(message());
                    return;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException exception) {
            // The launch ended first.
        }
    }

    /**
     * Stops every descendant of this JVM whose command line contains {@code marker}, together with all of their own
     * descendants, and returns a description of each process stopped.
     */
    static List<String> stopLaunch(String marker) throws InterruptedException {
        List<ProcessHandle> launched = ProcessHandle.current().descendants()
                .filter(process -> commandLine(process).contains(marker))
                .toList();
        Set<ProcessHandle> targets = new LinkedHashSet<>();
        for (ProcessHandle process : launched) {
            targets.add(process);
            process.descendants().forEach(targets::add);
        }
        List<String> described = targets.stream()
                .map(ClientStartupDeadline::describe)
                .collect(Collectors.toList());
        targets.forEach(ProcessHandle::destroy);
        awaitExit(targets);
        targets.stream().filter(ClientStartupDeadline::running).forEach(ProcessHandle::destroyForcibly);
        awaitExit(targets);
        List<String> survivors = targets.stream().filter(ClientStartupDeadline::running)
                .map(process -> Long.toString(process.pid()))
                .toList();
        if (!survivors.isEmpty()) {
            described.add("still running: " + String.join(", ", survivors));
        }
        return described;
    }

    private static void awaitExit(Set<ProcessHandle> processes) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATE_GRACE_SECONDS);
        while (processes.stream().anyMatch(ClientStartupDeadline::running) && System.nanoTime() - until < 0) {
            Thread.sleep(POLL_MILLIS);
        }
    }

    /**
     * Whether the process still exists and has not exited. {@link ProcessHandle#isAlive()} briefly reports a
     * process that is tearing itself down as gone, so on Linux the kernel's view decides: a zombie has exited.
     */
    static boolean running(ProcessHandle process) {
        Path stat = Path.of("/proc", Long.toString(process.pid()), "stat");
        if (!Files.isDirectory(stat.getParent())) {
            return Files.isDirectory(Path.of("/proc", "self")) ? false : process.isAlive();
        }
        try {
            String fields = Files.readString(stat, StandardCharsets.UTF_8);
            char state = fields.charAt(fields.lastIndexOf(')') + 2);
            return state != 'Z' && state != 'X';
        } catch (IOException | IndexOutOfBoundsException exception) {
            return false;
        }
    }

    private static String commandLine(ProcessHandle process) {
        Path procCommandLine = Path.of("/proc", Long.toString(process.pid()), "cmdline");
        try {
            return new String(Files.readAllBytes(procCommandLine), StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (IOException | UnsupportedOperationException exception) {
            return process.info().commandLine().orElse("");
        }
    }

    /** The process id and program name, with the script name for a shell running a script such as xvfb-run. */
    private static String describe(ProcessHandle process) {
        String[] words = commandLine(process).strip().split(" ");
        String program = baseName(words[0]);
        if (List.of("sh", "bash", "dash").contains(program)) {
            for (int index = 1; index < words.length; index++) {
                if (!words[index].startsWith("-")) {
                    program += " " + baseName(words[index]);
                    break;
                }
            }
        }
        return process.pid() + " " + program;
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static int positive(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            throw new GradleException(TIMEOUT_PROPERTY + " must be a positive number of seconds, not " + seconds);
        }
        return seconds;
    }
}
