package io.github.brainage04.minecraftmodconventions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the recording's input overlay shows: the keys held right now, each key and mouse button for a
 * short while after it was released, and the text typed into a screen.
 *
 * <p>A client GameTest presses a key for a single tick, which is one frame of a 30 fps recording, so a
 * press that only showed while it was held would be invisible. Every press therefore stays on screen
 * for {@link #MIN_VISIBLE_MILLIS} after its release, and a key held longer than that shows for as long
 * as it is held.
 *
 * <p>The class holds no game state and reads no clock: the caller passes the time, so the timing rules
 * are unit-tested without a client.
 */
public final class InputOverlayState {
    /** How long a press stays on screen after its release, so a one-tick click is readable at 30 fps. */
    public static final long MIN_VISIBLE_MILLIS = 400L;
    /** How long typed text stays on screen after the last character. */
    public static final long TYPED_VISIBLE_MILLIS = 2000L;
    /** The most key caps drawn at once; a test that holds more keys than this shows the first ones. */
    public static final int MAX_CHIPS = 8;
    /** The most typed characters kept, so a long command does not fill the frame. */
    public static final int MAX_TYPED_CHARS = 40;

    private final Map<String, Entry> keys = new LinkedHashMap<>();
    private final Map<Integer, Long> mouseReleasedAt = new LinkedHashMap<>();
    private final Map<Integer, Boolean> mouseHeld = new LinkedHashMap<>();
    private final StringBuilder typed = new StringBuilder();
    private long typedAt = Long.MIN_VALUE;

    /** A key cap: the key's label, and whether the key is still held. */
    public record Chip(String id, String label, boolean pressed) {
    }

    private static final class Entry {
        private final String label;
        private boolean pressed;
        private long releasedAt = Long.MIN_VALUE;

        private Entry(String label) {
            this.label = label;
        }
    }

    /** Records a key going down. A repeat of a key that is already down changes nothing. */
    public void keyDown(String id, String label, long nowMillis) {
        Entry entry = keys.get(id);
        if (entry == null) {
            entry = new Entry(label);
            keys.put(id, entry);
        }
        entry.pressed = true;
        entry.releasedAt = Long.MIN_VALUE;
    }

    /** Records a key coming up; it stays visible for {@link #MIN_VISIBLE_MILLIS} more. */
    public void keyUp(String id, long nowMillis) {
        Entry entry = keys.get(id);
        if (entry == null || !entry.pressed) {
            return;
        }
        entry.pressed = false;
        entry.releasedAt = nowMillis;
    }

    /** Records a mouse button going down. */
    public void mouseDown(int button) {
        mouseHeld.put(button, true);
        mouseReleasedAt.remove(button);
    }

    /** Records a mouse button coming up; it stays lit for {@link #MIN_VISIBLE_MILLIS} more. */
    public void mouseUp(int button, long nowMillis) {
        if (!Boolean.TRUE.equals(mouseHeld.get(button))) {
            return;
        }
        mouseHeld.put(button, false);
        mouseReleasedAt.put(button, nowMillis);
    }

    /** Whether the mouse glyph lights {@code button}: held, or released within the visible window. */
    public boolean mouseLit(int button, long nowMillis) {
        if (Boolean.TRUE.equals(mouseHeld.get(button))) {
            return true;
        }
        Long releasedAt = mouseReleasedAt.get(button);
        return releasedAt != null && nowMillis - releasedAt < MIN_VISIBLE_MILLIS;
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

    /** The key caps to draw, in the order the keys were first pressed. */
    public List<Chip> chips(long nowMillis) {
        List<Chip> chips = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : keys.entrySet()) {
            Entry value = entry.getValue();
            boolean visible = value.pressed
                    || (value.releasedAt != Long.MIN_VALUE
                            && nowMillis - value.releasedAt < MIN_VISIBLE_MILLIS);
            if (!visible) {
                continue;
            }
            chips.add(new Chip(entry.getKey(), value.label, value.pressed));
            if (chips.size() == MAX_CHIPS) {
                break;
            }
        }
        return chips;
    }

    /** Whether any mouse button is lit: held, or released within the visible window. */
    public boolean anyMouseLit(long nowMillis) {
        for (int button : List.of(1, 2, 3)) {
            if (mouseLit(button, nowMillis)) {
                return true;
            }
        }
        return false;
    }

    /** Whether anything at all is on screen, so an idle overlay draws nothing. */
    public boolean isEmpty(long nowMillis) {
        return chips(nowMillis).isEmpty() && typedText(nowMillis).isEmpty() && !anyMouseLit(nowMillis);
    }

    /** Forgets every key, button and typed character, for the start of a recording. */
    public void clear() {
        keys.clear();
        mouseHeld.clear();
        mouseReleasedAt.clear();
        typed.setLength(0);
        typedAt = Long.MIN_VALUE;
    }
}
