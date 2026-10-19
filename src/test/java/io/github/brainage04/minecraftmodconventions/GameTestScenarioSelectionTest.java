package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GameTestScenarioSelectionTest {
    @Test
    void emptyFiltersMatchAnyScenario() {
        GameTestScenarioSelection selection = new GameTestScenarioSelection(Set.of(), Set.of(), Set.of(), false);

        assertTrue(selection.matches("intro.case", "showcase", Set.of()));
        assertTrue(selection.matches("another.case", "regression", List.of("smoke")));
    }

    @Test
    void onlyAndSuiteFiltersMatchAfterTextNormalization() {
        GameTestScenarioSelection selection = new GameTestScenarioSelection(
                Set.of("  Focused.Case "),
                Set.of(" SMOKE "),
                Set.of(),
                false
        );

        assertTrue(selection.matches("focused.case", "unlisted", Set.of()));
        assertTrue(selection.matches("other.case", "smoke", Set.of()));
        assertFalse(selection.matches("other.case", "regression", Set.of()));
    }

    @Test
    void profileFiltersRequireANormalizedIntersection() {
        GameTestScenarioSelection selection = new GameTestScenarioSelection(
                Set.of(),
                Set.of(),
                Set.of(" Smoke ", "NIGHTLY"),
                false
        );

        assertTrue(selection.matches("any.case", "any-suite", List.of("release", " smoke ")));
        assertFalse(selection.matches("any.case", "any-suite", List.of("release", "showcase")));
        assertFalse(selection.matches("any.case", "any-suite", Set.of()));
    }

    @Test
    void allProfilesBypassesProfileFilteringWithoutBypassingOnlyOrSuiteFilters() {
        GameTestScenarioSelection selection = new GameTestScenarioSelection(
                Set.of("focused.case"),
                Set.of("smoke"),
                Set.of("nightly"),
                true
        );

        assertTrue(selection.matches("focused.case", "regression", Set.of()));
        assertTrue(selection.matches("other.case", "smoke", Set.of("unlisted")));
        assertFalse(selection.matches("other.case", "regression", Set.of("unlisted")));
    }

    @Test
    void constructorDropsBlankAndNullEntriesBeforeMatching() {
        GameTestScenarioSelection selection = new GameTestScenarioSelection(
                textSet("  Focused.Case  ", "", "   ", null, "FOCUSED.CASE"),
                textSet("", " Smoke ", null),
                textSet(" NIGHTLY ", "", null),
                false
        );

        assertEquals(Set.of("focused.case"), selection.onlyIds());
        assertEquals(Set.of("smoke"), selection.suites());
        assertEquals(Set.of("nightly"), selection.profiles());
        assertTrue(selection.matches(" focused.case ", "regression", List.of("nightly")));
        assertTrue(selection.matches("other.case", " smoke ", List.of("nightly")));
        assertFalse(selection.matches("other.case", "regression", List.of("nightly")));
    }

    @Test
    void constructorRejectsNullFilterSets() {
        assertThrows(NullPointerException.class, () -> new GameTestScenarioSelection(null, Set.of(), Set.of(), false));
        assertThrows(NullPointerException.class, () -> new GameTestScenarioSelection(Set.of(), null, Set.of(), false));
        assertThrows(NullPointerException.class, () -> new GameTestScenarioSelection(Set.of(), Set.of(), null, false));
    }

    @Test
    void fromEnvironmentParsesCommaSeparatedSelectorsInAnIsolatedJvm() throws Exception {
        Process process = startEnvironmentProbe();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Environment probe did not exit before the safety timeout.");
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), () -> output + error);
    }

    private static Process startEnvironmentProbe() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                EnvironmentProbe.class.getName()
        );
        builder.environment().put(GameTestRecorderEnvironment.TEST_ONLY_ENV, " Focused.Case , SECOND.CASE, focused.case ,, ");
        builder.environment().put(GameTestRecorderEnvironment.TEST_SUITE_ENV, " Smoke , Regression ");
        builder.environment().put(GameTestRecorderEnvironment.TEST_PROFILE_ENV, " smoke , all , NIGHTLY ");
        return builder.start();
    }

    private static Path javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static Set<String> textSet(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    public static final class EnvironmentProbe {
        public static void main(String[] args) {
            GameTestScenarioSelection selection = GameTestScenarioSelection.fromEnvironment();

            requireEquals(Set.of("focused.case", "second.case"), selection.onlyIds(), "only ids");
            requireEquals(Set.of("smoke", "regression"), selection.suites(), "suites");
            requireEquals(Set.of("smoke", "nightly"), selection.profiles(), "profiles");
            requireTrue(selection.allProfiles(), "all profile marker");
            requireTrue(selection.matches("second.case", "unlisted", Set.of()), "normalized id match");
            requireTrue(selection.matches("unlisted.case", "regression", List.of("unlisted")), "normalized suite match");
            requireFalse(selection.matches("unlisted.case", "unlisted", List.of("unlisted")), "id and suite gate still applies");
        }

        private static void requireEquals(Object expected, Object actual, String label) {
            if (!expected.equals(actual)) {
                throw new AssertionError("Expected " + label + " to be " + expected + " but was " + actual);
            }
        }

        private static void requireTrue(boolean value, String label) {
            if (!value) {
                throw new AssertionError("Expected " + label + " to be true.");
            }
        }

        private static void requireFalse(boolean value, String label) {
            if (value) {
                throw new AssertionError("Expected " + label + " to be false.");
            }
        }
    }
}
