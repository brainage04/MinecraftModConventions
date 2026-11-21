package io.github.brainage04.minecraftmodconventions;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the recording's keystrokes overlay shows: which keys the game received, each for a short while
 * after its release, and the text typed into a screen.
 *
 * <p>A client GameTest presses a key for a single tick, shorter than two frames of a 30 fps
 * recording, so a press that only showed while it was held would be invisible. Every press therefore
 * stays lit for {@link #MIN_VISIBLE_MILLIS} after its release, and a key held longer than that stays
 * lit for as long as it is held.
 *
 * <p>The class holds no game state and reads no clock: the caller passes the time, so the timing rules
 * are unit-tested without a client.
 */
public final class InputOverlayState {
    /** How long a press stays lit after its release, so a one-tick click is readable at 30 fps. */
    public static final long MIN_VISIBLE_MILLIS = 400L;
    /** How long typed text stays on screen after the last character. */
    public static final long TYPED_VISIBLE_MILLIS = 2000L;
    /** The most typed characters kept, so a long command does not fill the frame. */
    public static final int MAX_TYPED_CHARS = 40;

    private final Set<String> down = new HashSet<>();
    private final Map<String, Long> releasedAt = new LinkedHashMap<>();
    private final StringBuilder typed = new StringBuilder();
    private long typedAt = Long.MIN_VALUE;

    /** Records a key going down. A repeat of a key that is already down changes nothing. */
    public void keyDown(String id, long nowMillis) {
        down.add(id);
        releasedAt.remove(id);
    }

    /**
     * Forgets every key that is down, for a screen opening: the game releases every mapping there
     * without sending a release event ({@code Gui.java:263} in 26.3), so a key held at that moment
     * would otherwise stay lit for the rest of the recording.
     */
    public void releaseAll() {
        down.clear();
    }

    /** Records a key coming up; it stays lit for {@link #MIN_VISIBLE_MILLIS} more. */
    public void keyUp(String id, long nowMillis) {
        if (!down.remove(id)) {
            return;
        }
        releasedAt.put(id, nowMillis);
    }

    /** Whether the game received this key going down and has not received its release. */
    public boolean isDown(String id) {
        return down.contains(id);
    }

    /** Whether a key was released within the visible window, so its cap stays lit. */
    public boolean keyLit(String id, long nowMillis) {
        Long at = releasedAt.get(id);
        return at != null && nowMillis - at < MIN_VISIBLE_MILLIS;
    }

    /** Records a character typed into a screen, for the overlay's typed-text line. */
    public void typed(String text, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return;
        }
        typed.append(text);
        if (typed.length() > MAX_TYPED_CHARS) {
            typed.delete(0, typed.length() - MAX_TYPED_CHARS);
        }
        typedAt = nowMillis;
    }

    /** The text typed recently, or an empty string when nothing was typed in the last two seconds. */
    public String typedText(long nowMillis) {
        if (typedAt == Long.MIN_VALUE || nowMillis - typedAt >= TYPED_VISIBLE_MILLIS) {
            return "";
        }
        return typed.toString();
    }

    /** Forgets every key and typed character, for the start of a recording. */
    public void clear() {
        down.clear();
        releasedAt.clear();
        typed.setLength(0);
        typedAt = Long.MIN_VALUE;
    }
}
