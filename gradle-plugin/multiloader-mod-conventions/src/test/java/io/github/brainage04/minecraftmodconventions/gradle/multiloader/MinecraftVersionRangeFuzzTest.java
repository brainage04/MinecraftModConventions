package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import org.gradle.api.GradleException;
import org.junit.jupiter.params.provider.ValueSource;

class MinecraftVersionRangeFuzzTest {
    @ValueSource(strings = {"26.2", "26.2.1", "26.3-pre1", "26w14a", "1.21.8", "26.2147483648"})
    @FuzzTest(maxDuration = "60s")
    void supportedVersionsPreserveTheirLowerBound(@NotNull String input) {
        if (input.length() > 256) return;
        try {
            String range = MinecraftVersionRange.of(input);
            assertTrue(range.startsWith("[" + input + ","));
            assertTrue(range.endsWith(")"));
        } catch (GradleException invalidVersionShape) {
            // The public configuration parser explicitly rejects unsupported release shapes.
        }
    }
}
