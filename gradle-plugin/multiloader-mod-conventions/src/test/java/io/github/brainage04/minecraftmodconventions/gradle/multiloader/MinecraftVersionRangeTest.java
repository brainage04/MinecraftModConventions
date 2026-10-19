package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftVersionRangeTest {
    @Test
    void minorReleaseIsLimitedToItsMinorVersion() {
        assertEquals("[26.2,26.3)", MinecraftVersionRange.of("26.2"));
    }

    @Test
    void hotfixReleaseKeepsItsPatchAsTheLowerBound() {
        assertEquals("[26.2.1,26.3)", MinecraftVersionRange.of("26.2.1"));
    }

    @Test
    void upperBoundCarriesPastNineWithoutStringConcatenation() {
        assertEquals("[1.9.4,1.10)", MinecraftVersionRange.of("1.9.4"));
        assertEquals("[26.9,26.10)", MinecraftVersionRange.of("26.9"));
    }

    @Test
    void preReleaseKeepsItsSuffixInTheLowerBound() {
        assertEquals("[26.3-pre1,26.4)", MinecraftVersionRange.of("26.3-pre1"));
        assertEquals("[26.3-rc-1,26.4)", MinecraftVersionRange.of("26.3-rc-1"));
    }

    @Test
    void versionsWithoutAMinorVersionFailWithTheOffendingValue() {
        for (String version : new String[] {"26w14a", "26", "26.2.0.1", "26.2.", "", " 26.2"}) {
            GradleException exception = assertThrows(GradleException.class, () -> MinecraftVersionRange.of(version));
            assertTrue(exception.getMessage().contains("'" + version + "'"), exception.getMessage());
        }
    }
}
