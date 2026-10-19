package io.github.brainage04.minecraftmodconventions.gradle.workspace;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceDependenciesPluginTest {
    private static final String PLUGIN_ID = "io.github.brainage04.workspace-dependencies";

    @TempDir
    Path temporaryDirectory;

    Path projectDirectory;

    @BeforeEach
    void createProjectDirectory() throws IOException {
        projectDirectory = Files.createDirectory(temporaryDirectory.resolve("consumer"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'workspace-fixture'\n");
    }

    @Test
    void resolvesDeclaredModuleFromNamedLocalRepositoryBeforeCentral() throws IOException {
        writeMavenModule("FixtureLibrary", "example.fixture", "fixture-library", "1.0.0");
        writeBuildFile("""
                workspaceDependencies {
                    siblingMaven('FixtureLibrary') {
                        coordinate.set('example.fixture:fixture-library:1.0.0')
                    }
                }

                configurations { probe }
                dependencies { probe 'example.fixture:fixture-library:1.0.0' }

                tasks.register('verifyLocalResolution') {
                    doLast {
                        def repositories = project.repositories.toList()
                        assert repositories.first().name == 'WorkspaceFixtureLibraryLocal'
                        assert repositories.first().url == file('../FixtureLibrary/build/local-repo').toURI()
                        assert repositories.findIndexOf { it.name == 'WorkspaceFixtureLibraryLocal' } <
                                repositories.findIndexOf { it.hasProperty('url') && it.url.toString().contains('repo.maven.apache.org/maven2') }
                        assert configurations.probe.singleFile.name == 'fixture-library-1.0.0.jar'
                    }
                }
                """);

        BuildResult result = runGradle("verifyLocalResolution");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyLocalResolution").getOutcome());
    }

    @Test
    void resolvesMissingLocalPublicationFromMavenCentral() throws IOException {
        writeBuildFile("""
                workspaceDependencies {
                    siblingMaven('MissingCommonsLang') {
                        coordinate.set('org.apache.commons:commons-lang3:3.17.0')
                    }
                }

                configurations { probe }
                dependencies { probe 'org.apache.commons:commons-lang3:3.17.0' }

                tasks.register('verifyCentralFallback') {
                    doLast {
                        assert configurations.probe.singleFile.name == 'commons-lang3-3.17.0.jar'
                    }
                }
                """);

        BuildResult result = runGradle("verifyCentralFallback");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyCentralFallback").getOutcome());
    }

    @Test
    void failsWhenPublicationIsAbsentLocallyAndFromCentral() throws IOException {
        String coordinate = "org.apache.commons:commons-lang3:0.0.0-workspace-fixture-missing";
        writeBuildFile("""
                workspaceDependencies {
                    siblingMaven('MissingCommonsLang') {
                        coordinate.set('%s')
                    }
                }

                configurations { probe }
                dependencies { probe '%s' }

                tasks.register('resolveProbe') {
                    doLast {
                        configurations.probe.files
                    }
                }
                """.formatted(coordinate, coordinate));

        BuildResult result = runGradleAndFail("resolveProbe");

        assertTrue(result.getOutput().contains("Could not find " + coordinate));
    }


    @Test
    void declarationsSharingARepositoryReuseItAndRetainBothModuleFilters() throws IOException {
        writeMavenModule("Shared", "example.fixture", "first-library", "1.0.0");
        writeMavenModule("Shared", "example.fixture", "second-library", "2.0.0");
        writeBuildFile("""
                workspaceDependencies {
                    siblingMaven('First') {
                        coordinate.set('example.fixture:first-library:1.0.0')
                        siblingDirectory.set(layout.projectDirectory.dir('../Shared'))
                    }
                    siblingMaven('Second') {
                        coordinate.set('example.fixture:second-library:2.0.0')
                        siblingDirectory.set(layout.projectDirectory.dir('../Shared'))
                    }
                }

                configurations { probe }
                dependencies {
                    probe 'example.fixture:first-library:1.0.0'
                    probe 'example.fixture:second-library:2.0.0'
                }

                tasks.register('verifySharedRepository') {
                    doLast {
                        def sharedUrl = file('../Shared/build/local-repo').toURI()
                        assert project.repositories.findAll { it.hasProperty('url') && it.url == sharedUrl }.size() == 1
                        assert configurations.probe.files*.name.toSet() == [
                                'first-library-1.0.0.jar',
                                'second-library-2.0.0.jar'
                        ] as Set
                    }
                }
                """);

        BuildResult result = runGradle("verifySharedRepository");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifySharedRepository").getOutcome());
    }

    @Test
    void reconcilesDeclarationsAddedAfterAnUnrelatedConfigurationResolves() throws IOException {
        writeMavenModule("Early", "example.fixture", "early-library", "1.0.0");
        writeMavenModule("Late", "example.fixture", "late-library", "2.0.0");
        writeBuildFile("""
                workspaceDependencies {
                    siblingMaven('Early') {
                        coordinate.set('example.fixture:early-library:1.0.0')
                    }
                }

                configurations {
                    earlyProbe
                    lateProbe
                }
                dependencies {
                    earlyProbe 'example.fixture:early-library:1.0.0'
                    lateProbe 'example.fixture:late-library:2.0.0'
                }

                def earlyArtifact = configurations.earlyProbe.singleFile

                workspaceDependencies {
                    siblingMaven('Late') {
                        coordinate.set('example.fixture:late-library:2.0.0')
                    }
                }

                tasks.register('verifyLateDeclaration') {
                    doLast {
                        assert earlyArtifact.name == 'early-library-1.0.0.jar'
                        assert configurations.lateProbe.singleFile.name == 'late-library-2.0.0.jar'
                        assert repositories.find { it.name == 'WorkspaceLateLocal' } != null
                    }
                }
                """);

        BuildResult result = runGradle("verifyLateDeclaration");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyLateDeclaration").getOutcome());
    }

    @Test
    void keepsWorkingAlongsideExclusiveContentAcrossRepeatedResolutions() throws IOException {
        writeMavenModule("Sibling", "example.fixture", "sibling-library", "1.0.0");
        writeMavenModule("Niche", "example.niche", "niche-library", "3.0.0");
        writeBuildFile("""
                workspaceDependencies {
                    siblingMaven('Sibling') {
                        coordinate.set('example.fixture:sibling-library:1.0.0')
                    }
                }

                repositories {
                    exclusiveContent {
                        forRepository { maven { url = uri('%s') } }
                        filter { includeGroup 'example.niche' }
                    }
                }

                configurations {
                    firstProbe
                    secondProbe
                }
                dependencies {
                    firstProbe 'example.fixture:sibling-library:1.0.0'
                    secondProbe 'example.niche:niche-library:3.0.0'
                }

                def firstArtifact = configurations.firstProbe.singleFile

                tasks.register('verifyRepeatedResolution') {
                    doLast {
                        assert firstArtifact.name == 'sibling-library-1.0.0.jar'
                        assert configurations.secondProbe.singleFile.name == 'niche-library-3.0.0.jar'
                    }
                }
                """.formatted(temporaryDirectory.resolve("Niche/build/local-repo").toUri()));

        BuildResult result = runGradle("verifyRepeatedResolution");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyRepeatedResolution").getOutcome());
    }

    @Test
    void runClientLaunchesWithTheSharedOptionsFile() throws IOException {
        Path shared = temporaryDirectory.resolve("launcher instance/options.txt");
        Files.createDirectories(shared.getParent());
        Files.writeString(shared, "guiScale:3\n");
        writeRunClientFixture();
        Files.writeString(projectDirectory.resolve("run/client/options.txt"), "guiScale:0\n");

        BuildResult result = runGradle("runClient", "-P" + DevClientOptions.PROPERTY + "=" + shared);

        assertEquals(TaskOutcome.SUCCESS, result.task(":runClient").getOutcome());
        assertEquals("guiScale:3\n", Files.readString(projectDirectory.resolve("run/client/options.txt")));
    }

    @Test
    void runClientKeepsItsOwnOptionsWithoutTheProperty() throws IOException {
        writeRunClientFixture();
        Files.writeString(projectDirectory.resolve("run/client/options.txt"), "guiScale:0\n");

        BuildResult result = runGradle("runClient");

        assertEquals(TaskOutcome.SUCCESS, result.task(":runClient").getOutcome());
        assertEquals("guiScale:0\n", Files.readString(projectDirectory.resolve("run/client/options.txt")));
    }

    /** A stand-in for Loom's development client run: a JavaExec task named runClient in its own run directory. */
    private void writeRunClientFixture() throws IOException {
        Files.createDirectories(projectDirectory.resolve("run/client"));
        Files.createDirectories(projectDirectory.resolve("src/main/java"));
        Files.writeString(projectDirectory.resolve("src/main/java/Probe.java"),
                "public class Probe { public static void main(String[] args) { } }\n");
        writeBuildFile("""
                apply plugin: 'java'

                tasks.register('runClient', JavaExec) {
                    workingDir = file('run/client')
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'Probe'
                }
                """);
    }

    private void writeBuildFile(String configuration) throws IOException {
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id '%s'
                }

                %s
                """.formatted(PLUGIN_ID, configuration));
    }

    private void writeMavenModule(String sibling, String group, String artifact, String version) throws IOException {
        Path moduleDirectory = temporaryDirectory.resolve(sibling)
                .resolve("build/local-repo")
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version);
        Files.createDirectories(moduleDirectory);
        Files.writeString(moduleDirectory.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(group, artifact, version));
        try (var output = new JarOutputStream(Files.newOutputStream(
                moduleDirectory.resolve(artifact + "-" + version + ".jar")
        ))) {
            output.finish();
        }
    }

    private BuildResult runGradle(String... arguments) {
        return runner(arguments).build();
    }

    private BuildResult runGradleAndFail(String... arguments) {
        return runner(arguments).buildAndFail();
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }
}
