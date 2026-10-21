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
}
