package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientGameTestOptionsTest {
    private static Map<String, String> options(ClientGameTestOptions.Profile profile, String screen) {
        return ClientGameTestOptions.options(profile, "sink", true, screen, false, ClientGameTestOptions.DEFAULT_CAPTURE_FPS);
    }

    @Test
    void showcaseIsTheTestProfileWithViewGraphicsSoundAndScaleChanges() {
        Map<String, String> test = options(ClientGameTestOptions.Profile.TEST, "1920x1080");
        Map<String, String> showcase = options(ClientGameTestOptions.Profile.SHOWCASE, "1920x1080");

        assertEquals(test.keySet().stream().toList(), showcase.keySet().stream().toList());
        Map<String, String> changed = new TreeMap<>();
        showcase.forEach((key, value) -> {
            if (!value.equals(test.get(key))) {
                changed.put(key, value);
            }
        });
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                // Every recording uses the overlay standard: 1920x1080 at GUI scale 3.
                Map.entry("guiScale", "3"),
                Map.entry("overrideWidth", "1920"),
                Map.entry("overrideHeight", "1080"),
                Map.entry("renderDistance", "12"),
                Map.entry("graphicsPreset", "\"fancy\""),
                Map.entry("ao", "true"),
                Map.entry("cutoutLeaves", "true"),
                Map.entry("entityShadows", "true"),
                Map.entry("particles", "0"),
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
        Map<String, String> test = options(ClientGameTestOptions.Profile.TEST, "1920x1080");

        assertEquals("\"\"", test.get("soundDevice"));
        test.forEach((key, value) -> {
            if (key.startsWith("soundCategory_")) {
                assertEquals("0.0", value, key);
            }
        });
    }

    @Test
    void aTestRunKeepsTheSmallWindowAndItsOwnGuiScale() {
        Map<String, String> test = options(ClientGameTestOptions.Profile.TEST, "2560x1440x24");

        assertEquals(List.of("854", "480"), List.of(test.get("overrideWidth"), test.get("overrideHeight")));
        assertEquals("2", test.get("guiScale"));
        assertEquals("false", test.get("fullscreen"));
    }

    @Test
    void aRecordingOnACardDrawsAtTheCaptureRate() {
        Map<String, String> software = ClientGameTestOptions.options(
                ClientGameTestOptions.Profile.SHOWCASE, "sink", true, "1920x1080", false, 30);
        Map<String, String> card = ClientGameTestOptions.options(
                ClientGameTestOptions.Profile.SHOWCASE, "sink", true, "1920x1080", true, 30);
        Map<String, String> card60 = ClientGameTestOptions.options(
                ClientGameTestOptions.Profile.SHOWCASE, "sink", true, "1920x1080", true, 60);

        assertEquals("30", software.get("maxFps"));
        assertEquals("30", card.get("maxFps"));
        assertEquals("60", card60.get("maxFps"));
        assertEquals(software.keySet(), card.keySet());
    }

    @Test
    void theFrameCapStaysWithinWhatTheClientAccepts() {
        assertEquals(240, ClientGameTestOptions.frameCap(true, 240));
        assertEquals(260, ClientGameTestOptions.frameCap(true, 300));
        assertEquals(30, ClientGameTestOptions.frameCap(false, 60));
    }

    @Test
    void theCaptureRateIsAPositiveWholeNumber() {
        assertEquals(60, ClientGameTestOptions.parseCaptureFrameRate(" 60 "));
        for (String rate : new String[] {"0", "-30", "sixty", "29.97"}) {
            assertThrows(GradleException.class, () -> ClientGameTestOptions.parseCaptureFrameRate(rate), rate);
        }
    }

    @Test
    void onlyAClientOnTheCardReadsTheCaptureRate() {
        assertEquals(60, ClientGameTestOptions.captureFrameRate(true, "60"));
        assertEquals(30, ClientGameTestOptions.captureFrameRate(true, ""));
        assertThrows(GradleException.class, () -> ClientGameTestOptions.captureFrameRate(true, "sixty"));
        // A plain test run ignores the rate, so a bad value fails only the recording that reads it.
        assertEquals(30, ClientGameTestOptions.captureFrameRate(false, "sixty"));
        assertEquals(30, ClientGameTestOptions.captureFrameRate(false, "29.97"));
    }

    @Test
    void profilesAreNamedTestAndShowcase() {
        assertEquals(ClientGameTestOptions.Profile.TEST, ClientGameTestOptions.Profile.named("test"));
        assertEquals(ClientGameTestOptions.Profile.SHOWCASE, ClientGameTestOptions.Profile.named("showcase"));
        assertThrows(GradleException.class, () -> ClientGameTestOptions.Profile.named("fancy"));
    }

    @Test
    void theShowcaseWindowCoversTheRecorderScreenWithOrWithoutDepth() {
        Map<String, String> hd = options(ClientGameTestOptions.Profile.SHOWCASE, "1280x720x24");
        Map<String, String> qhd = options(ClientGameTestOptions.Profile.SHOWCASE, "2560x1440");

        assertEquals(List.of("1280", "720"), List.of(hd.get("overrideWidth"), hd.get("overrideHeight")));
        assertEquals(List.of("2560", "1440"), List.of(qhd.get("overrideWidth"), qhd.get("overrideHeight")));
        assertEquals("false", hd.get("fullscreen"));
    }

    @Test
    void aBadRecorderScreenFailsOnlyTheShowcaseProfile() {
        for (String screen : new String[] {"wide", "0x0x24", "1920"}) {
            assertThrows(GradleException.class,
                    () -> options(ClientGameTestOptions.Profile.SHOWCASE, screen), screen);
            Map<String, String> test = options(ClientGameTestOptions.Profile.TEST, screen);
            assertEquals(List.of("854", "480"), List.of(test.get("overrideWidth"), test.get("overrideHeight")), screen);
        }
    }
}
