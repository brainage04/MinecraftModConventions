package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOverlayStateTest {
    private static final long MIN_VISIBLE = InputOverlayState.MIN_VISIBLE_MILLIS;

    @Test
    void aHeldKeyShowsWhileItIsHeld() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.left.shift", "Shift", 1000L);

        assertEquals(List.of(new InputOverlayState.Chip("key.keyboard.left.shift", "Shift", true)),
                state.chips(1000L));
        assertEquals(List.of(new InputOverlayState.Chip("key.keyboard.left.shift", "Shift", true)),
                state.chips(1000L + 5000L));
    }

    @Test
    void aOneTickPressStaysVisibleForTheMinimumAfterItsRelease() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.right", "RMB", 1000L);
        state.keyUp("key.mouse.right", 1000L);

        // A press and release in the same millisecond is one frame of a 30 fps recording; it must still show.
        assertEquals(List.of(new InputOverlayState.Chip("key.mouse.right", "RMB", false)), state.chips(1000L));
        assertEquals(List.of(new InputOverlayState.Chip("key.mouse.right", "RMB", false)),
                state.chips(1000L + MIN_VISIBLE - 1));
        assertEquals(List.of(), state.chips(1000L + MIN_VISIBLE));
    }

    @Test
    void aKeyHeldLongerThanTheMinimumDisappearsWhenItIsReleased() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", "W", 1000L);
        state.keyUp("key.keyboard.w", 1000L + 3000L);

        assertEquals(List.of(new InputOverlayState.Chip("key.keyboard.w", "W", false)),
                state.chips(1000L + 3000L + MIN_VISIBLE - 1));
        assertEquals(List.of(), state.chips(1000L + 3000L + MIN_VISIBLE));
    }

    @Test
    void aRepeatOfAHeldKeyDoesNotRestartItsVisibleWindow() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", "W", 1000L);
        state.keyUp("key.keyboard.w", 2000L);
        // A repeat is a press of a key that is already down, so it must not clear the release time.
        state.keyDown("key.keyboard.w", "W", 2100L);

        assertEquals(List.of(new InputOverlayState.Chip("key.keyboard.w", "W", true)), state.chips(2100L));
    }

    @Test
    void aMouseClickWithNoKeyHeldIsNotAnEmptyOverlay() {
        InputOverlayState state = new InputOverlayState();
        state.mouseDown(3);

        assertFalse(state.isEmpty(1000L));
        assertTrue(state.anyMouseLit(1000L));
        state.mouseUp(3, 1000L);
        assertFalse(state.isEmpty(1000L + MIN_VISIBLE - 1));
        assertTrue(state.isEmpty(1000L + MIN_VISIBLE));
    }

    @Test
    void aReleaseWithoutAPressShowsNothing() {
        InputOverlayState state = new InputOverlayState();
        state.keyUp("key.keyboard.w", 1000L);

        assertEquals(List.of(), state.chips(1000L));
        assertTrue(state.isEmpty(1000L));
    }

    @Test
    void keysShowInTheOrderTheyWereFirstPressed() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.left.shift", "Shift", 1000L);
        state.keyDown("key.mouse.right", "RMB", 1000L);

        assertEquals(List.of("key.keyboard.left.shift", "key.mouse.right"),
                state.chips(1000L).stream().map(InputOverlayState.Chip::id).toList());
    }

    @Test
    void theChipListIsCapped() {
        InputOverlayState state = new InputOverlayState();
        for (int index = 0; index < InputOverlayState.MAX_CHIPS + 4; index++) {
            state.keyDown("key.keyboard." + index, "K" + index, 1000L);
        }

        assertEquals(InputOverlayState.MAX_CHIPS, state.chips(1000L).size());
    }

    @Test
    void aMouseButtonStaysLitForTheMinimumAfterItsRelease() {
        InputOverlayState state = new InputOverlayState();
        state.mouseDown(1);
        assertTrue(state.mouseLit(1, 1000L));
        assertFalse(state.mouseLit(3, 1000L));

        state.mouseUp(1, 1000L);
        assertTrue(state.mouseLit(1, 1000L + MIN_VISIBLE - 1));
        assertFalse(state.mouseLit(1, 1000L + MIN_VISIBLE));
    }

    @Test
    void aMouseReleaseWithoutAPressLightsNothing() {
        InputOverlayState state = new InputOverlayState();
        state.mouseUp(1, 1000L);

        assertFalse(state.mouseLit(1, 1000L));
    }

    @Test
    void typedTextShowsForTwoSecondsAfterTheLastCharacter() {
        InputOverlayState state = new InputOverlayState();
        state.typed("/", 1000L);
        state.typed("t", 1010L);
        state.typed("p", 1020L);

        assertEquals("/tp", state.typedText(1020L));
        assertEquals("/tp", state.typedText(1020L + InputOverlayState.TYPED_VISIBLE_MILLIS - 1));
        assertEquals("", state.typedText(1020L + InputOverlayState.TYPED_VISIBLE_MILLIS));
    }

    @Test
    void typedTextKeepsOnlyTheLastCharacters() {
        InputOverlayState state = new InputOverlayState();
        for (int index = 0; index < InputOverlayState.MAX_TYPED_CHARS + 10; index++) {
            state.typed("x", 1000L);
        }

        assertEquals(InputOverlayState.MAX_TYPED_CHARS, state.typedText(1000L).length());
    }

    @Test
    void anIdleOverlayIsEmpty() {
        InputOverlayState state = new InputOverlayState();

        assertTrue(state.isEmpty(1000L));
        state.keyDown("key.keyboard.w", "W", 1000L);
        assertFalse(state.isEmpty(1000L));
    }

    @Test
    void clearForgetsEverything() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", "W", 1000L);
        state.mouseDown(1);
        state.typed("x", 1000L);

        state.clear();

        assertTrue(state.isEmpty(1000L));
        assertFalse(state.mouseLit(1, 1000L));
        assertEquals("", state.typedText(1000L));
    }
}
