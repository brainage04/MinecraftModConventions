package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestCommandsTest {
    private static final double DELTA = 1.0e-9D;

    @Test
    void aCommandIsSentWithoutItsSlash() {
        assertEquals("home", ClientGameTestCommands.commandWithoutSlash("home"));
        assertEquals("home", ClientGameTestCommands.commandWithoutSlash("/home"));
        assertEquals("homeof Steve", ClientGameTestCommands.commandWithoutSlash("  /homeof Steve  "));
    }

    @Test
    void anEmptyCommandIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientGameTestCommands.commandWithoutSlash(""));
        assertThrows(IllegalArgumentException.class, () -> ClientGameTestCommands.commandWithoutSlash("   "));
        assertThrows(IllegalArgumentException.class, () -> ClientGameTestCommands.commandWithoutSlash("/"));
        assertThrows(NullPointerException.class, () -> ClientGameTestCommands.commandWithoutSlash(null));
    }

    @Test
    void theCommandTreeCheckUsesTheRootNameOfAnArgumentedCommand() {
        assertEquals("homeof", ClientGameTestCommands.commandName("homeof"));
        assertEquals("homeof", ClientGameTestCommands.commandName("/homeof Steve"));
        assertEquals("tpaccept", ClientGameTestCommands.commandName("tpaccept Player"));
    }

    @Test
    void aChatWaitNeedsEveryTextButNotOnOneLine() {
        List<String> messages = List.of("SimpleHomes: Home set", "Read-only home of Steve");

        assertTrue(ClientGameTestCommands.containsAll(messages, List.of("Home set")));
        assertTrue(ClientGameTestCommands.containsAll(messages, List.of("SimpleHomes", "Steve")));
        assertFalse(ClientGameTestCommands.containsAll(messages, List.of("Home deleted")));
        assertTrue(ClientGameTestCommands.containsAll(messages, List.of()));
    }

    @Test
    void aGuiPointIsScaledToTheWindow() {
        // 1920x1080 at GUI scale 3 is a 640x360 GUI, so each GUI pixel is 3 window pixels and the
        // point is the centre of the GUI pixel.
        double[] point = ClientGameTestCommands.screenPoint(10, 20, 640, 360, 1920, 1080);

        assertEquals(31.5D, point[0], DELTA);
        assertEquals(61.5D, point[1], DELTA);
    }

    @Test
    void theWindowPointScalesWithADifferentGuiSize() {
        double[] point = ClientGameTestCommands.screenPoint(0, 0, 427, 240, 854, 480);

        assertEquals(1.0D, point[0], DELTA);
        assertEquals(1.0D, point[1], DELTA);
    }

    @Test
    void anEmptyGuiIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestCommands.screenPoint(1, 1, 0, 360, 1920, 1080));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestCommands.screenPoint(1, 1, 640, 0, 1920, 1080));
    }

    @Test
    void aChatTimeoutIsPositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestCommands.awaitChat(new RecordingTestContext(), 0, "Home set"));
    }
}
