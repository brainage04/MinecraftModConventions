package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay's own event handling, without a client: a press is kept whether or not a screen had
 * the keyboard, and a cap is held only from its press to its release.
 */
class InputOverlayTest {
    private static final InputConstants.Key W = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_W);

    @BeforeEach
    void enableTheOverlay() {
        // The overlay draws only in a recording, and a unit test has none.
        InputOverlay.setEnabled(true);
    }

    @AfterEach
    void clearTheOverlay() {
        InputOverlay.clear();
    }

    @Test
    void aReleasedKeyIsNotHeldAnyMore() {
        // A viewer must see a key go up before it goes down again: the cap is lit while the key is
        // down, and the fade after the release is the cap's own, not a held-on lit state.
        InputOverlay.onKey(W, InputConstants.PRESS);
        InputOverlay.onKey(W, InputConstants.RELEASE);

        assertFalse(InputOverlay.isHeld(W.getName()));
    }

    @Test
    void aHeldKeyStaysHeldForAsLongAsItIsDown() {
        InputOverlay.onKey(W, InputConstants.PRESS);
        InputOverlay.onKey(W, InputConstants.REPEAT);

        assertTrue(InputOverlay.isHeld(W.getName()));
    }

    @Test
    void aKeyReleasedWhileTheOverlayIsHiddenIsNotHeldOnceShown() {
        InputOverlay.onKey(W, InputConstants.PRESS);
        InputOverlay.setEnabled(false);
        InputOverlay.onKey(W, InputConstants.RELEASE);
        InputOverlay.setEnabled(true);

        assertFalse(InputOverlay.isHeld(W.getName()));
    }
}
