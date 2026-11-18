package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOverlayLabelsTest {
    @Test
    void aModifierKeyShowsTheWordAPlayerSays() {
        assertEquals("Shift", InputOverlayLabels.label("key.keyboard.left.shift"));
        assertEquals("Shift", InputOverlayLabels.label("key.keyboard.right.shift"));
        assertEquals("Ctrl", InputOverlayLabels.label("key.keyboard.left.control"));
        assertEquals("Alt", InputOverlayLabels.label("key.keyboard.right.alt"));
    }

    @Test
    void aMouseButtonShowsItsAbbreviation() {
        assertEquals("LMB", InputOverlayLabels.label("key.mouse.left"));
        assertEquals("RMB", InputOverlayLabels.label("key.mouse.right"));
        assertEquals("MMB", InputOverlayLabels.label("key.mouse.middle"));
    }

    @Test
    void aLetterKeyShowsTheLetterInCapitals() {
        assertEquals("W", InputOverlayLabels.label("key.keyboard.w"));
        assertEquals("E", InputOverlayLabels.label("key.keyboard.e"));
        assertEquals("1", InputOverlayLabels.label("key.keyboard.1"));
    }

    @Test
    void aNamedKeyShowsItsName() {
        assertEquals("Space", InputOverlayLabels.label("key.keyboard.space"));
        assertEquals("Enter", InputOverlayLabels.label("key.keyboard.enter"));
        assertEquals("Esc", InputOverlayLabels.label("key.keyboard.escape"));
        assertEquals("F5", InputOverlayLabels.label("key.keyboard.f5"));
    }

    @Test
    void anUnknownNameKeepsItsLastWord() {
        assertEquals("Something", InputOverlayLabels.label("key.keyboard.something"));
        assertEquals("?", InputOverlayLabels.label(""));
        assertEquals("?", InputOverlayLabels.label(null));
    }

    @Test
    void everyLabelFitsAKeyCap() {
        for (String name : new String[] {
            "key.keyboard.left.shift", "key.keyboard.right.control", "key.keyboard.space",
            "key.keyboard.backspace", "key.keyboard.caps.lock", "key.mouse.middle"
        }) {
            String label = InputOverlayLabels.label(name);
            assertTrue(label.length() <= 5, name + " labels as " + label);
        }
    }
}
