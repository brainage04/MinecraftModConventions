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
    @Test
    void aVisiblePressLastsForTheCapsWholeFade() {
        // The cap lights over TRANSITION_NANOS while the key is down, so a click shorter than that never
        // reaches fully lit. Two ticks of 50 ms are the 100 ms fade.
        long pressMillis = (long) ClientGameTestPlayerActions.VISIBLE_PRESS_TICKS * SharedConstants.MILLIS_PER_TICK;

        assertEquals(2, ClientGameTestPlayerActions.VISIBLE_PRESS_TICKS);
        assertTrue(TimeUnit.MILLISECONDS.toNanos(pressMillis) >= InputOverlay.TRANSITION_NANOS);
    }

    @Test
    void aClickHoldsTheKeyForTheVisibleTicksThenReleasesIt() {
        List<String> calls = new ArrayList<>();
        RecordingTestContext context = new RecordingTestContext();
        context.input(recordingInput(calls));

        ClientGameTestPlayerActions.click(context, options -> options.keyAttack);

        assertEquals(List.of("holdKey", "releaseKey"), calls);
        assertEquals(List.of(ClientGameTestPlayerActions.VISIBLE_PRESS_TICKS), context.waits());
    }

    @Test
    void aMouseClickHoldsTheButtonForTheVisibleTicksThenReleasesIt() {
        List<String> calls = new ArrayList<>();
        RecordingTestContext context = new RecordingTestContext();
        context.input(recordingInput(calls));

        ClientGameTestPlayerActions.clickMouse(context, 0);

        assertEquals(List.of("holdMouse", "releaseMouse"), calls);
        assertEquals(List.of(ClientGameTestPlayerActions.VISIBLE_PRESS_TICKS), context.waits());
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
