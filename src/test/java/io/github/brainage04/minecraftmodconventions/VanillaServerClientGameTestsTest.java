package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VanillaServerClientGameTestsTest {
    @Test
    void onlyANonBlankAddressMeansThereIsAVanillaServer() {
        assertEquals(Optional.empty(), VanillaServerClientGameTests.serverAddress(null));
        assertEquals(Optional.empty(), VanillaServerClientGameTests.serverAddress("  "));
        assertEquals(Optional.of("127.0.0.1:25601"), VanillaServerClientGameTests.serverAddress(" 127.0.0.1:25601\n"));
    }

    @Test
    void extraChecksRunInTheOrderTheyWereAdded() {
        List<String> ran = new ArrayList<>();
        VanillaServerClientGameTests.CreativeItems items = VanillaServerClientGameTests.CreativeItems
                .of("Example", () -> false, _ -> false, () -> null)
                .withExtraChecks(_ -> ran.add("first"))
                .withExtraChecks(_ -> ran.add("second"));

        items.extraChecks().accept(null);

        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    void everyPartOfTheCheckIsRequired() {
        assertThrows(NullPointerException.class, () -> VanillaServerClientGameTests.CreativeItems
                .of("Example", null, _ -> false, () -> null));
        assertThrows(NullPointerException.class, () -> VanillaServerClientGameTests.CreativeItems
                .of("Example", () -> false, _ -> false, () -> null).withExtraChecks(null).extraChecks().accept(null));
    }
}
