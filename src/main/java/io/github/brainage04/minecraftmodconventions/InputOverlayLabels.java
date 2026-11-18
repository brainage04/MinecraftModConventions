package io.github.brainage04.minecraftmodconventions;

import java.util.Locale;
import java.util.Map;

/**
 * The short labels the input overlay's key caps show, from the key names vanilla registers
 * ({@code InputConstants.Type}'s {@code key.keyboard.*} and {@code key.mouse.*} names).
 *
 * <p>A cap has room for a few characters, so a name is shortened to the word a player would say:
 * {@code key.keyboard.left.shift} is {@code Shift}, {@code key.keyboard.space} is {@code Space} and
 * {@code key.mouse.right} is {@code RMB}. A name with no short form keeps its last word, upper-cased
 * when it is a single letter.
 */
public final class InputOverlayLabels {
    private static final Map<String, String> SHORT = Map.ofEntries(
            Map.entry("key.keyboard.left.shift", "Shift"),
            Map.entry("key.keyboard.right.shift", "Shift"),
            Map.entry("key.keyboard.left.control", "Ctrl"),
            Map.entry("key.keyboard.right.control", "Ctrl"),
            Map.entry("key.keyboard.left.alt", "Alt"),
            Map.entry("key.keyboard.right.alt", "Alt"),
            Map.entry("key.keyboard.left.win", "Meta"),
            Map.entry("key.keyboard.right.win", "Meta"),
            Map.entry("key.keyboard.space", "Space"),
            Map.entry("key.keyboard.enter", "Enter"),
            Map.entry("key.keyboard.escape", "Esc"),
            Map.entry("key.keyboard.backspace", "Bksp"),
            Map.entry("key.keyboard.tab", "Tab"),
            Map.entry("key.keyboard.caps.lock", "Caps"),
            Map.entry("key.keyboard.grave.accent", "`"),
            Map.entry("key.keyboard.minus", "-"),
            Map.entry("key.keyboard.equal", "="),
            Map.entry("key.keyboard.left.bracket", "["),
            Map.entry("key.keyboard.right.bracket", "]"),
            Map.entry("key.keyboard.backslash", "\\"),
            Map.entry("key.keyboard.semicolon", ";"),
            Map.entry("key.keyboard.apostrophe", "'"),
            Map.entry("key.keyboard.comma", ","),
            Map.entry("key.keyboard.period", "."),
            Map.entry("key.keyboard.slash", "/"),
            Map.entry("key.keyboard.up", "Up"),
            Map.entry("key.keyboard.down", "Down"),
            Map.entry("key.keyboard.left", "Left"),
            Map.entry("key.keyboard.right", "Right"),
            Map.entry("key.mouse.left", "LMB"),
            Map.entry("key.mouse.right", "RMB"),
            Map.entry("key.mouse.middle", "MMB"));

    private InputOverlayLabels() {
    }

    /** The cap label for a key name, such as {@code key.keyboard.left.shift} to {@code Shift}. */
    public static String label(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return "?";
        }
        String shortForm = SHORT.get(keyName);
        if (shortForm != null) {
            return shortForm;
        }
        String last = keyName.substring(keyName.lastIndexOf('.') + 1);
        if (last.isEmpty()) {
            return "?";
        }
        if (last.length() == 1) {
            return last.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }
}
