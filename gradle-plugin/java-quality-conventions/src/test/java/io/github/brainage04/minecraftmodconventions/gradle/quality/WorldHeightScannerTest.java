package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHeightScannerTest {
    private final WorldHeightScanner scanner = WorldHeightScanner.load();

    private List<Integer> hitLines(String source) {
        return scanner.scan(source.lines().toList()).stream().map(WorldHeightScanner.Finding::line).toList();
    }

    @Test
    void flagsTheOverworldLimitsInHeightCode() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), hitLines("""
                if (y < -64) return;
                if (level.getY() > 320) return;
                int maxY = 320;
                if (pos.getY() >= 319) return;
                BlockPos top = new BlockPos(x, 320, z);
                int minY = 0;
                int worldHeight = 256;
                """));
    }

    @Test
    void flagsOnlyModernLimitsNextToAPlainY() {
        assertEquals(List.of(1, 2, 3), hitLines("""
                if (y < -64) return;
                if (320 > y) return;
                int top = Math.min(320, y);
                if (y < 0) return;
                if (pos.getY() >= 0) return;
                BlockPos origin = new BlockPos(x, 0, z);
                """));
    }

    @Test
    void passesOtherNumbersAndOtherNames() {
        assertEquals(List.of(), hitLines("""
                if (y < 64) return;
                if (width == 256) return;
                int height = 256;
                if (height < 0) return;
                BlockPos corner = new BlockPos(0, 0, 0);
                if (sprite.getY() == 0) return;
                int distance = 319;
                """));
    }

    @Test
    void optOutWithAReasonOnTheLineOrTheLineAbove() {
        assertEquals(List.of(), hitLines("""
                if (y < -64) return; // world-height: this runs only on the 1.16.1 overworld
                // world-height: the form's layout is 256 pixels tall
                int maxBuildHeight = 256;
                """));
    }

    @Test
    void optOutWithoutAReasonStillWarns() {
        List<WorldHeightScanner.Finding> findings = scanner.scan(List.of(
                "// world-height:",
                "if (y < -64) return;",
                "if (y > 320) return;"));
        assertEquals(List.of(
                new WorldHeightScanner.Finding(2, "if (y < -64) return;", true),
                new WorldHeightScanner.Finding(3, "if (y > 320) return;", false)
        ), findings);
        assertTrue(findings.get(0).missingReason());
    }
}
