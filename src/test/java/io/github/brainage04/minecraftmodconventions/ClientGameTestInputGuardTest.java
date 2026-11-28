package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a recording a key or button must be released before a client GameTest presses it again.
 * Fabric's {@code TestInputImpl.holdKey} ignores a press of a key it already holds, and
 * {@code pressKey} then releases the key the earlier hold meant to keep. Outside a recording that
 * no-op is Fabric's documented contract and stays. These tests reach the check through Fabric's own
 * input class, as a film does.
 */
class ClientGameTestInputGuardTest {
    private static final InputConstants.Key LEFT_BUTTON =
            InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT);

    @Test
    void inARecordingHoldingAButtonThatIsAlreadyDownFailsAndNamesTheButton() throws ReflectiveOperationException {
        boolean before = ClientGameTestPlayerActions.strictHolds;
        ClientGameTestPlayerActions.strictHolds = true;
        Set<InputConstants.Key> down = keysDown();
        down.add(LEFT_BUTTON);
        try {
            AssertionError failure = assertThrows(
                    AssertionError.class, () -> new TestInputImpl(new RecordingTestContext()).holdKey(LEFT_BUTTON));

            assertTrue(failure.getMessage().contains(LEFT_BUTTON.getName()), failure.getMessage());
            assertTrue(failure.getMessage().contains("released"), failure.getMessage());
        } finally {
            down.remove(LEFT_BUTTON);
            ClientGameTestPlayerActions.strictHolds = before;
        }
    }

    @Test
    void outsideARecordingHoldingAButtonThatIsAlreadyDownStaysAFabricNoOp() throws ReflectiveOperationException {
        // The hold goes on to Fabric's own thread check, which a unit test fails: reaching it shows
        // the guard let the hold through.
        boolean before = ClientGameTestPlayerActions.strictHolds;
        ClientGameTestPlayerActions.strictHolds = false;
        Set<InputConstants.Key> down = keysDown();
        down.add(LEFT_BUTTON);
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> new TestInputImpl(new RecordingTestContext()).holdKey(LEFT_BUTTON));
        } finally {
            down.remove(LEFT_BUTTON);
            ClientGameTestPlayerActions.strictHolds = before;
        }
    }

    @Test
    void aButtonThatIsUpPassesTheCheckInARecording() throws ReflectiveOperationException {
        boolean before = ClientGameTestPlayerActions.strictHolds;
        ClientGameTestPlayerActions.strictHolds = true;
        try {
            assertFalse(keysDown().contains(LEFT_BUTTON));

            assertThrows(
                    IllegalStateException.class,
                    () -> new TestInputImpl(new RecordingTestContext()).holdKey(LEFT_BUTTON));
        } finally {
            ClientGameTestPlayerActions.strictHolds = before;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<InputConstants.Key> keysDown() throws ReflectiveOperationException {
        Field field = TestInputImpl.class.getDeclaredField("KEYS_DOWN");
        field.setAccessible(true);
        return (Set<InputConstants.Key>) field.get(null);
    }
}
