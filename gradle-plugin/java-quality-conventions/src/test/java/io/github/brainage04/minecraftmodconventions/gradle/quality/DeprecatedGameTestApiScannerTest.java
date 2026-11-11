package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeprecatedGameTestApiScannerTest {
    @Test
    void aMockPlayerInTheLevelIsAHit() {
        List<DeprecatedGameTestApiScanner.Finding> findings = DeprecatedGameTestApiScanner.scan(List.of(
                "class Trades {",
                "    void run(GameTestHelper helper) {",
                "        ServerPlayer player = helper.makeMockServerPlayerInLevel();",
                "    }",
                "}"));

        assertEquals(1, findings.size());
        assertEquals(3, findings.get(0).line());
        assertTrue(findings.get(0).source().contains("makeMockServerPlayerInLevel("));
    }

    @Test
    void theSupportedFactoriesAreNotHits() {
        List<DeprecatedGameTestApiScanner.Finding> findings = DeprecatedGameTestApiScanner.scan(List.of(
                "GameTestHelper helper;",
                "helper.makeMockServerPlayer(GameType.CREATIVE);",
                "helper.makeMockPlayer(GameType.SURVIVAL);"));

        assertEquals(List.of(), findings);
    }

    @Test
    void aCommentOrAStringAboutTheBannedMethodIsNotAHit() {
        List<DeprecatedGameTestApiScanner.Finding> findings = DeprecatedGameTestApiScanner.scan(List.of(
                "// makeMockServerPlayerInLevel() joins the player and is going away",
                "/* makeMockServerPlayerInLevel() */",
                "String name = \"makeMockServerPlayerInLevel\";",
                "int x = 0;"));

        assertEquals(List.of(), findings);
    }

    @Test
    void everyCallingLineIsReported() {
        List<DeprecatedGameTestApiScanner.Finding> findings = DeprecatedGameTestApiScanner.scan(List.of(
                "helper.makeMockServerPlayerInLevel();",
                "helper.makeMockServerPlayerInLevel(); helper.makeMockServerPlayerInLevel();"));

        assertEquals(2, findings.size());
        assertEquals(List.of(1, 2), findings.stream().map(DeprecatedGameTestApiScanner.Finding::line).toList());
    }

    @Test
    void aSuppressionOfRemovalWarningsIsReportedWithItsLine() {
        List<DeprecatedGameTestApiScanner.Suppression> suppressions = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "final class Legacy {",
                "    @SuppressWarnings(\"removal\")",
                "    static void old() {",
                "    }",
                "}"));

        assertEquals(1, suppressions.size());
        assertEquals(2, suppressions.get(0).line());
        assertTrue(suppressions.get(0).missingReason(), "a suppression without a marker needs a reason");
    }

    @Test
    void aMultiLineSuppressionIsReportedOnce() {
        List<DeprecatedGameTestApiScanner.Suppression> suppressions = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "@SuppressWarnings({",
                "        \"deprecation\",",
                "        \"unchecked\",",
                "})",
                "final class Legacy {",
                "}"));

        assertEquals(1, suppressions.size());
        assertEquals(1, suppressions.get(0).line());
    }

    @Test
    void otherSuppressionsAreNotReported() {
        List<DeprecatedGameTestApiScanner.Suppression> suppressions = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "@SuppressWarnings(\"unchecked\")",
                "@SuppressWarnings({\"rawtypes\", \"unchecked\"})",
                "// @SuppressWarnings(\"removal\")",
                "String text = \"@SuppressWarnings(\\\"removal\\\")\";"));

        assertEquals(List.of(), suppressions);
    }

    @Test
    void aReasonOnTheLineAboveOrOnTheAnnotationClearsTheSuppression() {
        List<DeprecatedGameTestApiScanner.Suppression> above = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "    // deprecated-api: the API is still present in this Minecraft version",
                "    @SuppressWarnings(\"removal\")",
                "    void old() {",
                "    }"));
        List<DeprecatedGameTestApiScanner.Suppression> sameLine = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "    @SuppressWarnings(\"removal\") // deprecated-api: kept until NeoForge patches it"));

        assertFalse(above.get(0).missingReason(), "the line above carries the reason");
        assertFalse(sameLine.get(0).missingReason(), "the annotation's own line carries the reason");
    }

    @Test
    void aCommentMarkerAfterAStringWithSlashesStillCounts() {
        List<DeprecatedGameTestApiScanner.Suppression> suppressions = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "    String docs = \"https://example.invalid/docs\"; // deprecated-api: the API is still present",
                "    @SuppressWarnings(\"removal\")"));

        assertFalse(
                suppressions.get(0).missingReason(),
                "the comment starts after the string literal, and its marker carries a reason");
    }

    @Test
    void aMarkerInsideAStringLiteralIsNotAComment() {
        List<DeprecatedGameTestApiScanner.Suppression> suppressions = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "    String marker = \"// deprecated-api: only text\";",
                "    @SuppressWarnings(\"removal\")"));

        assertTrue(suppressions.get(0).missingReason(), "slashes inside a literal do not start a comment");
    }

    @Test
    void aMarkerWithoutAReasonOrFurtherAwayDoesNotClearTheSuppression() {
        List<DeprecatedGameTestApiScanner.Suppression> reasonless = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "    // deprecated-api:",
                "    @SuppressWarnings(\"removal\")"));
        List<DeprecatedGameTestApiScanner.Suppression> twoLinesAbove = DeprecatedGameTestApiScanner.scanSuppressions(List.of(
                "    // deprecated-api: the API is still present",
                "",
                "    @SuppressWarnings(\"removal\")"));

        assertTrue(reasonless.get(0).missingReason(), "a marker without a reason does not count");
        assertTrue(twoLinesAbove.get(0).missingReason(), "the marker must be on the line above");
    }
}
