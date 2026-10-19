package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Starts real process trees that stand in for a client launch and lets the deadline run out on them. */
class ClientStartupDeadlineTest {
    @TempDir
    Path directory;

    @Test
    void anExpiredDeadlineStopsTheLaunchAndEveryProcessItStarted() throws Exception {
        Path ready = directory.resolve("ready");
        Process launch = start("sh", "-c", "sleep 600 & sleep 600 & wait", ClientStartupDeadline.jvmArgument(ready));
        List<ProcessHandle> started = awaitDescendants(launch, "sleep");

        ClientStartupDeadline deadline = ClientStartupDeadline.start(":fixture", ready, Duration.ofSeconds(2), Logging.getLogger(getClass()));

        assertTrue(launch.waitFor(30, TimeUnit.SECONDS), "the launch outlived its deadline");
        deadline.close();
        assertTrue(deadline.expired());
        assertNotEquals(0, launch.exitValue());
        started.forEach(process -> assertFalse(ClientStartupDeadline.running(process),
                "survived: " + process.pid() + " " + stat(process)));
        assertTrue(deadline.failure(null).getMessage().contains("did not finish loading within 2 s"),
                deadline.failure(null).getMessage());
    }

    @Test
    void anExpiredDeadlineStopsXvfbRunAndItsXvfb() throws Exception {
        assumeTrue(onPath("xvfb-run") && onPath("Xvfb"), "xvfb-run and Xvfb are not installed");
        Path ready = directory.resolve("ready");
        Process launch = start("xvfb-run", "-a", "sh", "-c", "sleep 600", ClientStartupDeadline.jvmArgument(ready));
        List<ProcessHandle> started = awaitDescendants(launch, "sleep");
        assertTrue(started.stream().anyMatch(process -> commandLine(process).contains("Xvfb")),
                "xvfb-run did not start Xvfb");

        ClientStartupDeadline deadline = ClientStartupDeadline.start(":fixture", ready, Duration.ofSeconds(2), Logging.getLogger(getClass()));

        assertTrue(launch.waitFor(30, TimeUnit.SECONDS), "xvfb-run outlived its deadline");
        deadline.close();
        assertTrue(deadline.expired());
        assertNotEquals(0, launch.exitValue());
        started.forEach(process -> assertFalse(ClientStartupDeadline.running(process),
                "survived: " + process.pid() + " " + stat(process)));
        String message = deadline.failure(null).getMessage();
        assertTrue(message.contains("xvfb-run") && message.contains("Xvfb"), message);
    }

    @Test
    void aClientThatSignalsInTimeKeepsRunning() throws Exception {
        Path ready = directory.resolve("ready");
        Process launch = start("sh", "-c", "sleep 600", ClientStartupDeadline.jvmArgument(ready));
        try {
            ClientStartupDeadline deadline = ClientStartupDeadline.start(":fixture", ready, Duration.ofSeconds(2), Logging.getLogger(getClass()));
            Files.writeString(ready, "loaded");
            Thread.sleep(3000L);
            deadline.close();

            assertFalse(deadline.expired());
            assertTrue(launch.isAlive());
        } finally {
            launch.destroyForcibly();
        }
    }

    private static Process start(String... command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Waits until the launch has started {@code program}, the innermost process, and returns all its descendants. */
    private static List<ProcessHandle> awaitDescendants(Process launch, String program) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        List<ProcessHandle> descendants = launch.descendants().toList();
        while (descendants.stream().noneMatch(process -> commandLine(process).startsWith(program + " "))
                && System.nanoTime() < until) {
            Thread.sleep(100L);
            descendants = launch.descendants().toList();
        }
        Thread.sleep(500L);
        descendants = launch.descendants().toList();
        assertTrue(descendants.stream().anyMatch(process -> commandLine(process).startsWith(program + " ")),
                "the launch has not started " + program + ": " + descendants);
        return descendants;
    }

    private static String stat(ProcessHandle process) {
        try {
            return Files.readString(Path.of("/proc", Long.toString(process.pid()), "stat"));
        } catch (IOException exception) {
            return "gone";
        }
    }

    /** The program's own argument list; on NixOS {@code ProcessHandle.Info} names the coreutils binary instead. */
    private static String commandLine(ProcessHandle process) {
        try {
            return Files.readString(Path.of("/proc", Long.toString(process.pid()), "cmdline")).replace('\0', ' ');
        } catch (IOException exception) {
            return "";
        }
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(entry, program))) {
                return true;
            }
        }
        return false;
    }
}
