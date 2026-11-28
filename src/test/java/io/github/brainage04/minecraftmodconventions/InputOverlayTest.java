package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay's own event handling, without a client: a press is kept whether or not a screen had
 * the keyboard, and a screen opening releases the keys that are down.
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
    void aOneTickPressStaysLitAfterItsRelease() {
        // A client GameTest presses a key for one tick, so the press and its release arrive in the
        // same millisecond and the cap must still light for the afterglow.
        InputOverlay.onKey(W, InputConstants.PRESS);
        InputOverlay.onKey(W, InputConstants.RELEASE);

        // Util.getMillis is the clock the overlay reads (Util.java:180-182), not the epoch clock.
        assertTrue(InputOverlay.keyLit(W.getName(), Util.getMillis()));
        assertFalse(InputOverlay.keyLit(W.getName(), Util.getMillis() + InputOverlayState.MIN_VISIBLE_MILLIS));
    }

    @Test
    void aReleasedKeyIsNotLitAnyMore() {
        // A viewer must see a key go up before it goes down again: the cap is lit while the key is
        // down, and the fade after the release is the cap's own, not a held-on lit state.
        InputOverlay.onKey(W, InputConstants.PRESS);
        InputOverlay.onKey(W, InputConstants.RELEASE);

        assertFalse(InputOverlay.keyLit(W.getName(), Util.getMillis()));
    }

    @Test
    void aHeldKeyStaysLitForAsLongAsItIsHeld() {
        InputOverlay.onKey(W, InputConstants.PRESS);

        assertTrue(InputOverlay.keyLit(W.getName(), Util.getMillis() + 60_000L));
    }

    @Test
    void aScreenOpeningReleasesTheKeysThatAreDownAfterTheirAfterglow() {
        InputOverlay.onKey(W, InputConstants.PRESS);
        assertTrue(InputOverlay.keyLit(W.getName(), Util.getMillis()));

        InputOverlay.releaseAll();

        assertTrue(InputOverlay.keyLit(W.getName(), Util.getMillis()));
        assertFalse(InputOverlay.keyLit(W.getName(), Util.getMillis() + InputOverlayState.MIN_VISIBLE_MILLIS));
    }

    @Test
    void aKeyReleasedWhileTheOverlayIsHiddenGoesDarkOnceShown() {
        InputOverlay.onKey(W, InputConstants.PRESS);
        InputOverlay.setEnabled(false);
        InputOverlay.onKey(W, InputConstants.RELEASE);
        InputOverlay.setEnabled(true);

        assertFalse(InputOverlay.keyLit(W.getName(), Util.getMillis() + InputOverlayState.MIN_VISIBLE_MILLIS));
    }
}
