package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaQualityConventionsPluginTest {
    @Test
    void withNeitherGroupOnEveryLintIsAWarning() {
        List<String> arguments = JavaQualityConventionsPlugin.lintArguments(false, false);

        assertEquals(List.of("-Xlint:deprecation", "-Xlint:unchecked", "-Xlint:rawtypes"), arguments);
        assertFalse(arguments.contains("-Werror"), "no group means no failing warning");
    }

    @Test
    void withOnlyTheDeprecationGroupADeprecatedCallFailsTheBuild() {
        List<String> arguments = JavaQualityConventionsPlugin.lintArguments(true, false);

        assertEquals(List.of("-Xlint:deprecation", "-Werror"), arguments);
        // An enabled unchecked lint would fail the build too, because -Werror covers every warning.
        assertFalse(arguments.contains("-Xlint:unchecked"));
        assertFalse(arguments.contains("-Xlint:rawtypes"));
    }

    @Test
    void withOnlyTheUncheckedGroupUncheckedAndRawTypesFailTheBuild() {
        List<String> arguments = JavaQualityConventionsPlugin.lintArguments(false, true);

        assertEquals(List.of("-Xlint:unchecked", "-Xlint:rawtypes", "-Werror"), arguments);
        assertFalse(arguments.contains("-Xlint:deprecation"));
    }

    @Test
    void withBothGroupsOnEveryLintFailsTheBuild() {
        List<String> arguments = JavaQualityConventionsPlugin.lintArguments(true, true);

        assertEquals(
                List.of("-Xlint:deprecation", "-Xlint:unchecked", "-Xlint:rawtypes", "-Werror"),
                arguments);
        assertTrue(arguments.contains("-Werror"));
    }
}
