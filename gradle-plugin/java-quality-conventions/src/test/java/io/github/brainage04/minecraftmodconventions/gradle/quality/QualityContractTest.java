package io.github.brainage04.minecraftmodconventions.gradle.quality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityContractTest {
    @TempDir Path directory;

    private GradleRunner fixture(String source, String... arguments) throws IOException {
        Files.writeString(
                directory.resolve("settings.gradle"), "rootProject.name='quality-contract'\n");
        Files.writeString(
                directory.resolve("gradle.properties"),
                "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=1\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins {
                    id 'java'
                    id 'io.github.brainage04.java-quality-conventions'
                }
                repositories { mavenCentral() }
                """);
        var target = directory.resolve("src/main/java/Policy.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
        // Embedded TestKit stays inside the caller's heavy process instead of leaving daemons.
        return GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withDebug(true)
                .withArguments(arguments);
    }

    @Test
    void formattingIsOnWithoutWerror() throws IOException {
        var result = fixture("final class Policy{}", "spotlessJavaCheck").buildAndFail();
        assertTrue(result.getOutput().contains("format violations"));
    }

    @Test
    void checkstyleDoesNotNeedCompilationOrFormatting() throws IOException {
        var result =
                fixture(
                                "import java.util.*;\nfinal class Policy {}\n",
                                "checkstyleMain",
                                "-PqualityFormatting=false")
                        .buildAndFail();
        assertTrue(result.getOutput().contains("AvoidStarImport"));
    }

    @Test
    void selectedCorrectnessDiagnosticsDoNotForceWerror() throws IOException {
        var result =
                fixture(
                                "class Policy { void run() { new IllegalArgumentException(); } }",
                                "compileJava")
                        .build();
        assertTrue(result.getOutput().contains("[DeadException]"));
    }

    @Test
    void werrorIsIndependent() throws IOException {
        var result =
                fixture(
                                "class Policy { void run() { new IllegalArgumentException(); } }",
                                "compileJava",
                                "-PqualityWerror=true",
                                "-PqualityFormatting=false",
                                "-PqualityCheckstyle=false")
                        .buildAndFail();
        assertTrue(result.getOutput().contains("warnings found and -Werror specified"));
    }

    @Test
    void nullMarkedScopeIsChecked() throws IOException {
        var result =
                fixture(
                                "@org.jspecify.annotations.NullMarked class Policy { String value() { return null; } }",
                                "compileJava")
                        .buildAndFail();
        assertTrue(result.getOutput().contains("[NullAway]"));
    }

    @Test
    void unannotatedBoundariesAreNotPretendedNonNull() throws IOException {
        fixture("class Policy { String value() { return null; } }", "compileJava").build();
    }

    @Test
    void additionalRulesReportBeforeEnforcementAndAllowCommentedCatches() throws IOException {
        String source =
                """
                class Policy {
                    void run(boolean ready) {
                        if (ready) System.gc();
                        try { System.gc(); } catch (RuntimeException ignored) {}
                        try { System.gc(); } catch (RuntimeException ignored) {
                            // A deliberate, documented no-op.
                        }
                    }
                }
                """;
        fixture(source, "checkstyleAdditional").build();
        String report =
                Files.readString(
                        directory.resolve("build/reports/checkstyle/checkstyleAdditional.xml"));
        assertTrue(report.contains("NeedBracesCheck"));
        assertTrue(report.split("EmptyCatchBlockCheck", -1).length == 2);
        fixture(source, "checkstyleAdditional", "-PqualityCheckstyleAdditionalEnforce=true")
                .buildAndFail();
    }

    @Test
    void magicNumbersUseOnlyMainAndRetainRequestedExemptions() throws IOException {
        var runner =
                fixture(
                        """
                @interface Marker { int value() default 91; }
                @Marker(91)
                class Policy {
                    static final int CONSTANT = 42;
                    public int hashCode() { return 31; }
                    int value() { return 77; }
                }
                """,
                        "checkstyleMagicNumbers",
                        "-PqualityCheckstyleAdditionalEnforce=true");
        var gameTest = directory.resolve("src/gametest/java/GameTestNumbers.java");
        Files.createDirectories(gameTest.getParent());
        Files.writeString(gameTest, "class GameTestNumbers { int value() { return 97; } }");
        runner.build();
        String report =
                Files.readString(
                        directory.resolve("build/reports/checkstyle/checkstyleMagicNumbers.xml"));
        assertTrue(report.split("MagicNumberCheck", -1).length == 2);
        assertTrue(!report.contains("GameTestNumbers"));
    }

    @Test
    void importBoundaryDistinguishesServerAndClientProduction() throws IOException {
        String source = "package example; import net.minecraft.client.Minecraft; class Policy {}";
        fixture(source, "checkstyleImports", "-Pmod_side=server").build();
        assertTrue(
                Files.readString(
                                directory.resolve("build/reports/checkstyle/checkstyleImports.xml"))
                        .contains("ImportControlCheck"));
        var client = fixture(source, "checkstyleImports", "-Pmod_side=client");
        Files.writeString(
                directory.resolve("build.gradle"),
                "\ntasks.named('checkstyleImports') { println('IMPORT_SOURCES=' + source.files) }\n",
                java.nio.file.StandardOpenOption.APPEND);
        var result = client.build();
        assertTrue(result.getOutput().contains("IMPORT_SOURCES=[]"), result.getOutput());
    }
}
