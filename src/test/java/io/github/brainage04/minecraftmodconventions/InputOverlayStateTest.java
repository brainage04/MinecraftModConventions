package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

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
    void theOverlayKeepsNoTypedText() {
        // The chat box already shows what a player types, so the overlay draws no second copy of it
        // (owner, 28 Nov 2026): nothing in the overlay or its state records a typed character.
        for (Class<?> type : List.of(InputOverlayState.class, InputOverlay.class)) {
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(typedTextName(method.getName()), type.getSimpleName() + "." + method.getName());
            }
            for (Field field : type.getDeclaredFields()) {
                assertFalse(typedTextName(field.getName()), type.getSimpleName() + "." + field.getName());
            }
        }
    }

    private static boolean typedTextName(String name) {
        return name.toLowerCase(Locale.ROOT).contains("typed") || name.equals("onChar");
    }

    @Test
    void clearForgetsEverything() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);
        state.keyUp("key.keyboard.w", 1000L);

        state.clear();

        assertFalse(state.isDown("key.keyboard.w"));
        assertFalse(state.keyLit("key.keyboard.w", 1000L));
        assertEquals(0L, state.presses("key.keyboard.w"));
    }

    @Test
    void everyPressIsCountedAndARepeatOfAHeldKeyIsNot() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);
        state.keyDown("key.mouse.left", 1100L);
        assertEquals(1L, state.presses("key.mouse.left"));

        state.keyUp("key.mouse.left", 1200L);
        state.keyDown("key.mouse.left", 1250L);

        assertEquals(2L, state.presses("key.mouse.left"));
        assertEquals(0L, state.presses("key.mouse.right"));
    }

    @Test
    void twoClicksInsideOneAfterglowDarkenTheCapBetweenThem() {
        // The Telekinesis item-frame take: a left click, five ticks (250 ms) later another, each a
        // press and a release in one tick. The afterglow is 400 ms, so the cap stays lit from the
        // first press to 400 ms after the second unless the second press darkens it.
        long frame = 33L;
        long frameNanos = frame * 1_000_000L;
        InputOverlayState state = new InputOverlayState();
        float progress = 0.0F;
        float darkest = 1.0F;
        long seen = 0L;
        for (long now = 0L; now <= 700L; now += frame) {
            if (state.presses("key.mouse.left") == 0L) {
                state.keyDown("key.mouse.left", now);
                state.keyUp("key.mouse.left", now);
            }
            if (now >= 250L && state.presses("key.mouse.left") == 1L) {
                state.keyDown("key.mouse.left", now);
                state.keyUp("key.mouse.left", now);
            }
            boolean lit = state.isDown("key.mouse.left") || state.keyLit("key.mouse.left", now);
            long presses = state.presses("key.mouse.left");
            progress = InputOverlay.nextProgress(progress, lit, frameNanos, presses != seen);
            seen = presses;
            if (now > 100L && now < 600L) {
                darkest = Math.min(darkest, progress);
            }
        }

        assertEquals(0.0F, darkest, 1.0E-6F);
    }

    @Test
    void twoClicksFiveTicksApartShowTheCapGoingDarkBetweenThemForTheWholeFade() {
        // A film click holds the key for two ticks (100 ms) and the next starts five ticks (250 ms)
        // after the first. The cap is lit while the key is down and fades out over TRANSITION_NANOS
        // after the release, so between the clicks it goes from lit to dark: a viewer sees the key
        // go up before it goes down again.
        long step = 10L;
        long stepNanos = step * 1_000_000L;
        long fadeMillis = InputOverlay.TRANSITION_NANOS / 1_000_000L;
        InputOverlayState state = new InputOverlayState();
        float progress = 0.0F;
        long dimmingMillis = 0L;
        float darkest = 1.0F;
        for (long now = 0L; now <= 600L; now += step) {
            if (now == 0L || now == 250L) {
                state.keyDown("key.mouse.left", now);
            }
            if (now == 100L || now == 350L) {
                state.keyUp("key.mouse.left", now);
            }
            boolean lit = state.isDown("key.mouse.left") || state.keyLit("key.mouse.left", now);
            progress = InputOverlay.advance(progress, lit, stepNanos);
            if (now > 100L && now <= 250L) {
                darkest = Math.min(darkest, progress);
                if (progress < 1.0F) {
                    dimmingMillis += step;
                }
            }
        }

        assertEquals(0.0F, darkest, 1.0E-6F);
        assertTrue(dimmingMillis >= fadeMillis, "dimmed for " + dimmingMillis + " ms");
    }

    @Test
    void onlyAPressThatFindsTheCapLitRestartsIt() {
        // A first press lights the cap over the normal fade, and a cap with no new press is never
        // restarted.
        assertEquals(0.33F, InputOverlay.nextProgress(0.0F, true, 33_000_000L, true), 1.0E-6F);
        assertEquals(1.0F, InputOverlay.nextProgress(1.0F, true, 33_000_000L, false), 1.0E-6F);
        assertEquals(0.0F, InputOverlay.nextProgress(0.8F, true, 33_000_000L, true), 1.0E-6F);
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
