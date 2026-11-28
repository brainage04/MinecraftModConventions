package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOverlayStateTest {
    @Test
    void aHeldKeyIsDownUntilItsRelease() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);

        assertTrue(state.isDown("key.keyboard.w"));
        state.keyUp("key.keyboard.w");
        assertFalse(state.isDown("key.keyboard.w"));
    }

    @Test
    void aRepeatOfAHeldKeyChangesNothing() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.w", 1000L);
        // A repeat is a press of a key that is already down: it is not a second click.
        state.keyDown("key.keyboard.w", 1100L);

        assertEquals(1, state.clicksPerSecond("key.keyboard.w", 1100L));
        state.keyUp("key.keyboard.w");
        assertFalse(state.isDown("key.keyboard.w"));
    }

    @Test
    void aPressAfterAReleaseIsDownAgain() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.keyboard.space", 1000L);
        state.keyUp("key.keyboard.space");
        assertFalse(state.isDown("key.keyboard.space"));

        state.keyDown("key.keyboard.space", 1200L);

        assertTrue(state.isDown("key.keyboard.space"));
    }

    @Test
    void aReleaseWithoutAPressChangesNothing() {
        InputOverlayState state = new InputOverlayState();
        state.keyUp("key.keyboard.left.shift");

        assertFalse(state.isDown("key.keyboard.left.shift"));
    }

    @Test
    void aClickThatOpensAScreenStaysDownUntilItsRelease() {
        // A click on a chat link runs its command inside the press, and the command opens the chat
        // screen again. The click's own release still arrives, and the cap is lit until it does.
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 1000L);

        assertTrue(state.isDown("key.mouse.left"));
        state.keyUp("key.mouse.left");
        assertFalse(state.isDown("key.mouse.left"));
        assertEquals(1, state.clicksPerSecond("key.mouse.left", 1050L));
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

        state.clear();

        assertFalse(state.isDown("key.keyboard.w"));
        assertEquals(0, state.clicksPerSecond("key.keyboard.w", 1000L));
    }

    @Test
    void twoClicksFiveTicksApartShowTheCapGoingDarkBetweenThemForTheWholeFade() {
        // A film click holds the key for two ticks (100 ms) and the next starts five ticks (250 ms)
        // after the first. The cap is lit while the key is down and fades out over TRANSITION_NANOS
        // after the release, so between the clicks it goes from lit to dark: a viewer sees the key
        // go up before it goes down again.
        long step = 10L;
        long stepNanos = TimeUnit.MILLISECONDS.toNanos(step);
        long fadeMillis = TimeUnit.NANOSECONDS.toMillis(InputOverlay.TRANSITION_NANOS);
        InputOverlayState state = new InputOverlayState();
        float progress = 0.0F;
        long dimmingMillis = 0L;
        float darkest = 1.0F;
        for (long now = 0L; now <= 600L; now += step) {
            if (now == 0L || now == 250L) {
                state.keyDown("key.mouse.left", now);
            }
            if (now == 100L || now == 350L) {
                state.keyUp("key.mouse.left");
            }
            progress = InputOverlay.advance(progress, state.isDown("key.mouse.left"), stepNanos);
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
    void aHeldKeyStaysLitAcrossFrames() {
        InputOverlayState state = new InputOverlayState();
        state.keyDown("key.mouse.left", 0L);
        float progress = 0.0F;
        for (long now = 0L; now <= 5000L; now += 33L) {
            progress = InputOverlay.advance(progress, state.isDown("key.mouse.left"), 33_000_000L);
        }

        assertEquals(1.0F, progress, 1.0E-6F);
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
        state.keyUp("key.mouse.left");
        state.keyDown("key.mouse.left", 1100L);
        state.keyUp("key.mouse.left");

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
        state.keyUp("key.mouse.left");
        state.keyDown("key.mouse.left", 1020L);
        state.keyUp("key.mouse.left");
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

    private static final String LEFT = "key.mouse.left";

    @Test
    void aRawPressKeyIsDrawnLitThenDarkEvenThoughItIsOverBeforeAFrame() {
        // Fabric's pressKey holds and releases a key in one phase, before the next frame renders.
        InputOverlayState state = new InputOverlayState();
        state.keyDown(LEFT, 1000L);
        state.keyUp(LEFT);

        InputOverlayState.Frame first = state.frame(LEFT);
        InputOverlayState.Frame second = state.frame(LEFT);
        InputOverlayState.Frame third = state.frame(LEFT);

        assertTrue(first.lit());
        assertTrue(first.instant());
        assertFalse(second.lit());
        assertFalse(third.lit());
    }

    @Test
    void aReleaseBetweenTwoPressesInOnePhaseIsDrawnAsADarkFrame() {
        // Two film clicks back to back: hold, release and hold again before any frame renders.
        InputOverlayState state = new InputOverlayState();
        state.keyDown(LEFT, 1000L);
        state.keyUp(LEFT);
        state.keyDown(LEFT, 1000L);

        assertTrue(state.frame(LEFT).lit());
        assertFalse(state.frame(LEFT).lit());
        assertTrue(state.frame(LEFT).lit());
        assertTrue(state.frame(LEFT).lit());
    }

    @Test
    void twoRawPressesFiveTicksApartAreTwoFlashesWithADarkCapBetween() {
        // The Telekinesis item-frame take: pressKey, four ticks, pressKey. Frames come every 33 ms.
        long frame = 33L;
        InputOverlayState state = new InputOverlayState();
        float progress = 0.0F;
        int flashes = 0;
        boolean darkSinceFlash = false;
        boolean darkBetween = true;
        for (long now = 0L; now <= 600L; now += frame) {
            if (now == 0L || now >= 250L && now < 250L + frame) {
                state.keyDown(LEFT, now);
                state.keyUp(LEFT);
            }
            float before = progress;
            progress = InputOverlay.progressAfter(
                    progress, state.frame(LEFT), false, TimeUnit.MILLISECONDS.toNanos(frame));
            if (progress == 1.0F && before < 1.0F) {
                if (flashes > 0) {
                    darkBetween &= darkSinceFlash;
                }
                flashes++;
                darkSinceFlash = false;
            } else if (progress == 0.0F) {
                darkSinceFlash = true;
            }
        }

        assertEquals(2, flashes);
        assertTrue(darkBetween, "the cap did not go dark between the two presses");
    }
}
