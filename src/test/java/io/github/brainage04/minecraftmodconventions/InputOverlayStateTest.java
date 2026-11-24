package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOverlayStateTest {
    private static final long MIN_VISIBLE = InputOverlayState.MIN_VISIBLE_MILLIS;

    @Test
    void aHeldKeyIsDownUntilItsRelease() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);

        assertTrue(state.isDown("key.keyboard.w"));
        state.keyUp("key.keyboard.w", 1200L);
        assertFalse(state.isDown("key.keyboard.w"));
    }

    @Test
    void aOneTickPressStaysLitForTheMinimumAfterItsRelease() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.right", 1000L);
        state.keyUp("key.mouse.right", 1000L);

        // A press and release in the same millisecond is one frame of a 30 fps recording; it must still show.
        assertTrue(state.keyLit("key.mouse.right", 1000L));
        assertTrue(state.keyLit("key.mouse.right", 1000L + MIN_VISIBLE - 1));
        assertFalse(state.keyLit("key.mouse.right", 1000L + MIN_VISIBLE));
    }

    @Test
    void aKeyHeldLongerThanTheMinimumGoesDarkWhenItIsReleased() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);
        state.keyUp("key.keyboard.w", 1000L + 3000L);

        assertTrue(state.keyLit("key.keyboard.w", 1000L + 3000L + MIN_VISIBLE - 1));
        assertFalse(state.keyLit("key.keyboard.w", 1000L + 3000L + MIN_VISIBLE));
    }

    @Test
    void aRepeatOfAHeldKeyChangesNothing() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);
        // A repeat is a press of a key that is already down: it must not restart the press.
        state.keyDown("key.keyboard.w", 1100L);

        assertFalse(state.keyLit("key.keyboard.w", 1100L));
        state.keyUp("key.keyboard.w", 2000L);
        assertFalse(state.keyLit("key.keyboard.w", 2000L + MIN_VISIBLE));
    }

    @Test
    void aPressAfterAReleaseLightsAgain() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.space", 1000L);
        state.keyUp("key.keyboard.space", 1000L);
        state.keyDown("key.keyboard.space", 1200L);

        assertFalse(state.keyLit("key.keyboard.space", 1200L));
        state.keyUp("key.keyboard.space", 1300L);
        assertTrue(state.keyLit("key.keyboard.space", 1300L));
    }

    @Test
    void aReleaseWithoutAPressLightsNothing() {
        InputOverlayState state = new InputOverlayState();
        state.keyUp("key.keyboard.left.shift", 1000L);

        assertFalse(state.keyLit("key.keyboard.left.shift", 1000L));
    }

    @Test
    void aClickThatOpensAScreenStaysLitForTheMinimum() {
        InputOverlayState state = new InputOverlayState();
        // A click on a chat link runs its command inside the press, and the command opens the chat
        // screen again, which releases every key before the click's own release arrives.
        state.keyDown("key.mouse.left", 1000L);
        state.releaseAll(1000L);
        state.keyUp("key.mouse.left", 1050L);

        assertFalse(state.isDown("key.mouse.left"));
        assertTrue(state.keyLit("key.mouse.left", 1050L));
        assertTrue(state.keyLit("key.mouse.left", 1000L + MIN_VISIBLE - 1));
        assertFalse(state.keyLit("key.mouse.left", 1000L + MIN_VISIBLE));
    }

    @Test
    void aClickThatOpensAScreenCountsOnceAndAnotherAfterItCountsAgain() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);
        state.releaseAll(1000L);
        state.keyUp("key.mouse.left", 1050L);

        assertEquals(1, state.clicksPerSecond("key.mouse.left", 1050L));
        assertTrue(state.keyLit("key.mouse.left", 1050L));

        state.keyDown("key.mouse.left", 1100L);

        assertEquals(2, state.clicksPerSecond("key.mouse.left", 1100L));
    }

    @Test
    void typedTextShowsForTwoSeconds() {
        InputOverlayState state = new InputOverlayState();
        state.typed("m", 1000L);
        state.typed("a", 1100L);

        assertEquals("ma", state.typedText(1100L));
        assertEquals("ma", state.typedText(1100L + InputOverlayState.TYPED_VISIBLE_MILLIS - 1));
        assertEquals("", state.typedText(1100L + InputOverlayState.TYPED_VISIBLE_MILLIS));
    }

    @Test
    void typedTextKeepsTheLastFortyCharacters() {
        InputOverlayState state = new InputOverlayState();
        for (int index = 0; index < 50; index++) {
            state.typed(Integer.toString(index % 10), 1000L + index);
        }

        String text = state.typedText(1050L);
        assertEquals(InputOverlayState.MAX_TYPED_CHARS, text.length());
        assertEquals("0123456789012345678901234567890123456789", text);
    }

    @Test
    void clearForgetsEverything() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);
        state.keyUp("key.keyboard.w", 1000L);
        state.typed("x", 1000L);

        state.clear();

        assertFalse(state.isDown("key.keyboard.w"));
        assertFalse(state.keyLit("key.keyboard.w", 1000L));
        assertEquals("", state.typedText(1000L));
    }

    @Test
    void theCountIsZeroBeforeAnyClick() {
        InputOverlayState state = new InputOverlayState();

        assertEquals(0, state.clicksPerSecond("key.mouse.left", 1000L));
    }

    @Test
    void aClickCountsForOneSecondAndThenDecays() {
        // BrainageHUD's ClickRateTracker: a click stays for 1000 ms and is dropped once
        // now - click >= 1000 (ClickRateTracker.java:6, :21).
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);
        state.keyUp("key.mouse.left", 1050L);
        state.keyDown("key.mouse.left", 1100L);
        state.keyUp("key.mouse.left", 1150L);

        assertEquals(2, state.clicksPerSecond("key.mouse.left", 1100L));
        assertEquals(2, state.clicksPerSecond("key.mouse.left", 1999L));
        assertEquals(1, state.clicksPerSecond("key.mouse.left", 2000L));
        assertEquals(1, state.clicksPerSecond("key.mouse.left", 2099L));
        assertEquals(0, state.clicksPerSecond("key.mouse.left", 2100L));
    }

    @Test
    void eachButtonCountsItsOwnClicks() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);
        state.keyUp("key.mouse.left", 1010L);
        state.keyDown("key.mouse.left", 1020L);
        state.keyUp("key.mouse.left", 1030L);
        state.keyDown("key.mouse.right", 1040L);

        assertEquals(2, state.clicksPerSecond("key.mouse.left", 1040L));
        assertEquals(1, state.clicksPerSecond("key.mouse.right", 1040L));
    }

    @Test
    void aHeldButtonIsOneClick() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);
        state.keyDown("key.mouse.left", 1100L);
        state.keyDown("key.mouse.left", 1200L);

        assertEquals(1, state.clicksPerSecond("key.mouse.left", 1200L));
    }

    @Test
    void clearForgetsTheClicks() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);

        state.clear();

        assertEquals(0, state.clicksPerSecond("key.mouse.left", 1000L));
    }
}
