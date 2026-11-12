package io.github.brainage04.minecraftmodconventions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects caption text that tells the viewer what was removed or changed. A caption describes what the mod
 * does in the scenario on screen.
 */
final class CaptionWording {
    private static final Pattern CHANGE_WORDING = Pattern.compile(
            "\\b(no longer|anymore|(?:is|are) gone|(?:was|were|has been|have been) (?:removed|changed|replaced)"
                    + "|(?:has|have) changed)\\b",
            Pattern.CASE_INSENSITIVE);

    private CaptionWording() {
    }

    /** Fails when {@code text} says what was removed or changed; a null or blank text passes. */
    static void requireDescribesTheMod(String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = CHANGE_WORDING.matcher(text);
        if (matcher.find()) {
            throw new IllegalArgumentException("The caption \"" + text.strip() + "\" says what was removed or changed (\""
                    + matcher.group(1) + "\"). Describe what the mod does in this scenario instead.");
        }
    }
}
