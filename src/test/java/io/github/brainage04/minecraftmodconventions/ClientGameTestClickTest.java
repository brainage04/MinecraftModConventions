package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestClickTest {
    private static final int PRESS = ClientGameTestPlayerActions.VISIBLE_PRESS_TICKS;
    private static final int GAP = ClientGameTestPlayerActions.RELEASED_GAP_TICKS;

    @Test
    void aVisiblePressLastsForTheCapsWholeFade() {
        // The cap lights over TRANSITION_NANOS while the key is down, so a click shorter than that never
        // reaches fully lit. Two ticks of 50 ms are the 100 ms fade.
        long pressMillis = (long) PRESS * SharedConstants.MILLIS_PER_TICK;

        assertEquals(2, PRESS);
        assertTrue(TimeUnit.MILLISECONDS.toNanos(pressMillis) >= InputOverlay.TRANSITION_NANOS);
    }

    @Test
    void theReleasedGapIsAFrameAndTheFadeLong() {
        // One frame at 30 frames a second is 34 ms (rounded up), and the cap fades over 100 ms.
        long gapMillis = (long) GAP * SharedConstants.MILLIS_PER_TICK;
        long frameAndFadeMillis = 34L + TimeUnit.NANOSECONDS.toMillis(InputOverlay.TRANSITION_NANOS);

        assertEquals(3, GAP);
        assertTrue(gapMillis >= frameAndFadeMillis);
    }

    @Test
    void aClickHoldsTheKeyThenReleasesItAndWaitsForTheReleasedGap() {
        List<String> calls = new ArrayList<>();
        RecordingTestContext context = new RecordingTestContext();
        context.input(recordingInput(calls));

        ClientGameTestPlayerActions.click(context, options -> options.keyAttack);

        assertEquals(List.of("holdKey", "releaseKey"), calls);
        assertEquals(List.of(PRESS, GAP), context.waits());
    }

    @Test
    void aMouseClickHoldsTheButtonThenReleasesItAndWaitsForTheReleasedGap() {
        List<String> calls = new ArrayList<>();
        RecordingTestContext context = new RecordingTestContext();
        context.input(recordingInput(calls));

        ClientGameTestPlayerActions.clickMouse(context, 0);

        assertEquals(List.of("holdMouse", "releaseMouse"), calls);
        assertEquals(List.of(PRESS, GAP), context.waits());
    }

    @Test
    void twoClicksBackToBackLeaveAReleasedGapBetweenThem() {
        // The release of the first click must not reach the press of the second in one phase: a
        // frame has to draw the cap released, and the cap has to fade, in between.
        List<String> calls = new ArrayList<>();
        RecordingTestContext context = new RecordingTestContext();
        context.input(recordingInput(calls));

        ClientGameTestPlayerActions.click(context, options -> options.keyAttack);
        ClientGameTestPlayerActions.click(context, options -> options.keyAttack);

        assertEquals(List.of("holdKey", "releaseKey", "holdKey", "releaseKey"), calls);
        assertEquals(List.of(PRESS, GAP, PRESS, GAP), context.waits());
    }

    @Test
    void aClickReleasesTheKeyWhenTheWaitFails() {
        List<String> calls = new ArrayList<>();
        RecordingTestContext context = new RecordingTestContext() {
            @Override
            public void waitTicks(int ticks) {
                throw new IllegalStateException("the test failed while the key was held");
            }
        };
        context.input(recordingInput(calls));

        assertThrows(
                IllegalStateException.class,
                () -> ClientGameTestPlayerActions.click(context, options -> options.keyUse));

        assertEquals(List.of("holdKey", "releaseKey"), calls);
    }

    private static TestInput recordingInput(List<String> calls) {
        return (TestInput) Proxy.newProxyInstance(
                TestInput.class.getClassLoader(),
                new Class<?>[] {TestInput.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });
    }
}
