package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * An exclusive claim on a recording X display, held for the whole client run.
 *
 * <p>Two recordings that share a display both draw on it, and x11grab would capture a mix of the two
 * clients without saying so. A recorder therefore takes the display's lock, and by default the second one
 * falls back to its own virtual display instead of waiting: a waiting job would hold a heavy slot until the
 * first recording ended, while the fallback still produces a usable take and names the reason in the
 * recording's metadata. A recording that must be on the card waits for the lock instead, for a bounded
 * time ({@link #acquireWithin}).
 */
final class ExclusiveDisplayLock implements AutoCloseable {
    private static final String LOCK_PREFIX = ".gtr-recording-display-";
    private static final long POLL_MILLIS = 1_000L;

    private final FileChannel channel;
    private final FileLock lock;

    private ExclusiveDisplayLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** The lock file of a display: {@code /tmp/.gtr-recording-display-1.lock} for {@code :1}. */
    static File lockFile(String display) {
        String name = display == null ? "" : display.strip();
        if (name.startsWith(":")) {
            name = name.substring(1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (name.isEmpty()) {
            name = "default";
        }
        return new File(System.getProperty("java.io.tmpdir", "/tmp"), LOCK_PREFIX + name + ".lock");
    }

    /**
     * Takes the display's lock without waiting, or returns null when another recording holds it. A
     * lock file that cannot be opened throws, because that is an environment problem and the caller
     * must not record on the display without holding it.
     */
    static ExclusiveDisplayLock tryAcquire(String display) {
        Path path = lockFile(display).toPath();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new ExclusiveDisplayLock(channel, lock);
        } catch (OverlappingFileLockException heldByThisJvm) {
            closeQuietly(channel);
            return null;
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new UncheckedIOException("Cannot take the recording display lock " + path, exception);
        }
    }

    /**
     * Takes the display's lock, waiting up to {@code wait} for the recording that holds it to stop; null
     * when the lock is still held after that. The lock is tried once a second.
     */
    static ExclusiveDisplayLock acquireWithin(String display, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            ExclusiveDisplayLock lock = tryAcquire(display);
            if (lock != null) {
                return lock;
            }
            long leftMillis = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
            if (leftMillis <= 0) {
                return null;
            }
            Thread.sleep(Math.min(POLL_MILLIS, leftMillis));
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException exception) {
            // Closing the channel releases the lock as well.
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            // Nothing else holds this channel.
        }
    }
}
