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
 * <p>A key is down from its press to its release. The cap lights while it is down and fades over
 * {@link InputOverlay#TRANSITION_NANOS} after the release, so two clicks a few ticks apart read as
 * two clicks. A press or release that happens between two frames is kept until a frame has drawn it
 * ({@link #frame}), so a test's instant {@code pressKey} is seen.
 *
 * <p>The class holds no game state and reads no clock: the caller passes the time, so the timing rules
 * are unit-tested without a client.
 */
public final class InputOverlayState {
    /** How long a click counts towards the clicks per second: BrainageHUD's {@code ClickRateTracker.WINDOW_MILLIS}. */
    public static final long CLICK_WINDOW_MILLIS = 1000L;
    /**
     * The most undrawn presses and releases kept for one key, so a recording that is not drawing the
     * overlay does not grow a queue it never empties. A test never has this many in flight.
     */
    static final int MAX_UNDRAWN_EVENTS = 64;

    private final Set<String> down = new HashSet<>();
    private final Map<String, Deque<Long>> clicks = new HashMap<>();
    /** Presses and releases of each key that no frame has drawn yet, oldest first; true is a press. */
    private final Map<String, Deque<Boolean>> undrawn = new HashMap<>();

    /**
     * Records a key going down, and counts it as a click. A repeat of a key that is already down
     * changes nothing.
     */
    public void keyDown(String id, long nowMillis) {
        if (down.add(id)) {
            Deque<Long> times = clicks.computeIfAbsent(id, key -> new ArrayDeque<>());
            times.addLast(nowMillis);
            expireClicks(times, nowMillis);
            drawLater(id, true);
        }
    }

    /** Records a key coming up. */
    public void keyUp(String id) {
        if (down.remove(id)) {
            drawLater(id, false);
        }
    }

    /** Whether the game received this key going down and has not received its release. */
    public boolean isDown(String id) {
        return down.contains(id);
    }

    /**
     * What one frame draws for a key. {@code lit} is whether the cap is lit; {@code instant} is that
     * the press lighting it was already over, so the cap flashes to full; {@code queued} is that more
     * undrawn presses or releases of the key are waiting for later frames, so the cap's own mapping
     * must not decide this frame.
     */
    public record Frame(boolean lit, boolean instant, boolean queued) {}

    /**
     * The state the next frame draws for a key, taking one undrawn press or release off the key's
     * queue per call. A test presses and releases a key in one phase, before any frame renders, so
     * sampling {@link #isDown} alone would never draw it. Each press is therefore drawn lit for one
     * frame and each release dark for one frame, in the order they arrived, and with none waiting the
     * frame follows the key's real state.
     */
    public Frame frame(String id) {
        Deque<Boolean> events = undrawn.get(id);
        if (events == null || events.isEmpty()) {
            return new Frame(down.contains(id), false, false);
        }
        boolean pressed = events.pollFirst();
        return new Frame(pressed, pressed && !down.contains(id), !events.isEmpty());
    }

    private void drawLater(String id, boolean pressed) {
        Deque<Boolean> events = undrawn.computeIfAbsent(id, key -> new ArrayDeque<>());
        if (events.size() >= MAX_UNDRAWN_EVENTS) {
            events.pollFirst();
        }
        events.addLast(pressed);
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

    /** Forgets every key, click and undrawn press, for the start of a recording. */
    public void clear() {
        down.clear();
        clicks.clear();
        undrawn.clear();
    }
}
