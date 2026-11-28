package io.github.brainage04.minecraftmodconventions;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the recording's keystrokes overlay knows: which keys the game received going down and not yet
 * up, and the presses of the last second for the clicks-per-second row.
 *
 * <p>A key is down from its press to its release and no longer. The cap's own fade
 * ({@link InputOverlay#TRANSITION_NANOS}) is what a viewer sees after the release, so two clicks a few
 * ticks apart read as two clicks. A client GameTest that wants a click to be seen holds its key for
 * {@link ClientGameTestPlayerActions#VISIBLE_PRESS_TICKS}.
 *
 * <p>The class holds no game state and reads no clock: the caller passes the time, so the timing rules
 * are unit-tested without a client.
 */
public final class InputOverlayState {
    /** How long a click counts towards the clicks per second: BrainageHUD's {@code ClickRateTracker.WINDOW_MILLIS}. */
    public static final long CLICK_WINDOW_MILLIS = 1000L;

    private final Set<String> down = new HashSet<>();
    private final Map<String, Deque<Long>> clicks = new HashMap<>();

    /**
     * Records a key going down, and counts it as a click. A repeat of a key that is already down
     * changes nothing.
     */
    public void keyDown(String id, long nowMillis) {
        if (down.add(id)) {
            Deque<Long> times = clicks.computeIfAbsent(id, key -> new ArrayDeque<>());
            times.addLast(nowMillis);
            expireClicks(times, nowMillis);
        }
    }

    /** Records a key coming up. */
    public void keyUp(String id) {
        down.remove(id);
    }

    /** Whether the game received this key going down and has not received its release. */
    public boolean isDown(String id) {
        return down.contains(id);
    }

    /** What one frame draws for a key: whether the cap is lit, and whether it flashes to full. */
    public record Frame(boolean lit, boolean instant, boolean queued) {}

    /** The state the next frame draws for a key. */
    public Frame frame(String id) {
        return new Frame(down.contains(id), false, false);
    }

    /**
     * How many times a key went down in the last {@link #CLICK_WINDOW_MILLIS}, counted as BrainageHUD's
     * {@code ClickRateTracker} does: a click stays for the whole window and is dropped once
     * {@code now - click >= window}, and the count is the number of clicks left (a whole number, 0
     * with none).
     */
    public int clicksPerSecond(String id, long nowMillis) {
        Deque<Long> times = clicks.get(id);
        if (times == null) {
            return 0;
        }
        expireClicks(times, nowMillis);
        return times.size();
    }

    private static void expireClicks(Deque<Long> times, long nowMillis) {
        while (!times.isEmpty() && nowMillis - times.peekFirst() >= CLICK_WINDOW_MILLIS) {
            times.pollFirst();
        }
    }

    /** Forgets every key and click, for the start of a recording. */
    public void clear() {
        down.clear();
        clicks.clear();
    }
}
