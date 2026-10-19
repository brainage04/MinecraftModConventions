package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientGameTestOptionsTest {
    @Test
    void showcaseIsTheTestProfileWithViewGraphicsAndSoundChanges() {
        Map<String, String> test = ClientGameTestOptions.options(ClientGameTestOptions.Profile.TEST, "sink", true);
        Map<String, String> showcase = ClientGameTestOptions.options(ClientGameTestOptions.Profile.SHOWCASE, "sink", true);

        assertEquals(test.keySet().stream().toList(), showcase.keySet().stream().toList());
        Map<String, String> changed = new TreeMap<>();
        showcase.forEach((key, value) -> {
            if (!value.equals(test.get(key))) {
                changed.put(key, value);
            }
        });
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("fullscreen", "true"),
                Map.entry("renderDistance", "12"),
                Map.entry("graphicsPreset", "\"fancy\""),
                Map.entry("ao", "true"),
                Map.entry("cutoutLeaves", "true"),
                Map.entry("entityShadows", "true"),
                Map.entry("soundDevice", "\"sink\""),
                Map.entry("soundCategory_master", "0.7"),
                Map.entry("soundCategory_record", "1.0"),
                Map.entry("soundCategory_weather", "1.0"),
                Map.entry("soundCategory_block", "1.0"),
                Map.entry("soundCategory_hostile", "1.0"),
                Map.entry("soundCategory_neutral", "1.0"),
                Map.entry("soundCategory_player", "1.0"),
                Map.entry("soundCategory_ambient", "1.0"),
                Map.entry("soundCategory_voice", "1.0"),
                Map.entry("soundCategory_ui", "1.0")
        ));
        assertEquals(expected, changed);
    }

    @Test
    void theTestProfileIsSilentWhateverTheSoundDevice() {
        Map<String, String> test = ClientGameTestOptions.options(ClientGameTestOptions.Profile.TEST, "sink", true);

        assertEquals("\"\"", test.get("soundDevice"));
        test.forEach((key, value) -> {
            if (key.startsWith("soundCategory_")) {
                assertEquals("0.0", value, key);
            }
        });
    }

    @Test
    void profilesAreNamedTestAndShowcase() {
        assertEquals(ClientGameTestOptions.Profile.TEST, ClientGameTestOptions.Profile.named("test"));
        assertEquals(ClientGameTestOptions.Profile.SHOWCASE, ClientGameTestOptions.Profile.named("showcase"));
        assertThrows(GradleException.class, () -> ClientGameTestOptions.Profile.named("fancy"));
    }
}
