package io.github.brainage04.minecraftmodconventions.gradle.central;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCentralPublishingPluginTest {
    private static final String PLUGIN_ID = "io.github.brainage04.maven-central-publishing";

    @TempDir
    Path temporaryDirectory;

    Path projectDirectory;

    @BeforeEach
    void createProjectDirectory() throws IOException {
        projectDirectory = Files.createDirectory(temporaryDirectory.resolve("consumer"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'publishing-fixture'\n");
    }

    @Test
    void appliesPublishingComponentsWithoutFabricAndRemainsIdempotent() throws IOException {
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id '%s'
                }
                apply plugin: io.github.brainage04.minecraftmodconventions.gradle.central.MavenCentralPublishingPlugin

                tasks.register('verifyPublishingComponents') {
                    doLast {
                        assert project.pluginManager.hasPlugin('maven-publish')
                        assert project.pluginManager.hasPlugin('signing')
                        assert !project.pluginManager.hasPlugin('net.fabricmc.fabric-loom')
                        assert !project.pluginManager.hasPlugin('io.github.brainage04.fabric-mod-conventions')
                        assert project.extensions.findByName('mavenCentralPublishing') != null
                        assert project.publishing.repositories.count { it.name == 'local' } == 1
                        assert project.publishing.repositories.count { it.name == 'central' } == 1
                        assert tasks.validateMavenPublicationMetadata instanceof io.github.brainage04.minecraftmodconventions.gradle.central.ValidateMavenPublicationMetadataTask
                        assert tasks.validateMavenCentralPublishing instanceof io.github.brainage04.minecraftmodconventions.gradle.central.ValidateMavenCentralPublishingTask
                        assert tasks.publishToMavenCentral instanceof io.github.brainage04.minecraftmodconventions.gradle.central.FinalizeMavenCentralDeploymentTask
                    }
                }
                """.formatted(PLUGIN_ID));

        BuildResult result = runGradle("verifyPublishingComponents");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyPublishingComponents").getOutcome());
    }

    @Test
    void publishesLocallyWithoutSecretsAndWritesExactPomMetadata() throws IOException {
        writePublicationFixture();

        BuildResult result = runGradle("publishMavenJavaPublicationToLocalRepository");

        assertEquals(TaskOutcome.SUCCESS, result.task(":publishMavenJavaPublicationToLocalRepository").getOutcome());
        Path moduleDirectory = projectDirectory.resolve(
                "build/local-repo/io/github/brainage04/fixture-library/1.2.3"
        );
        assertTrue(Files.isRegularFile(moduleDirectory.resolve("fixture-library-1.2.3.jar")));
        Path pomPath = moduleDirectory.resolve("fixture-library-1.2.3.pom");
        assertTrue(Files.isRegularFile(pomPath));
        String pom = Files.readString(pomPath);
        assertTrue(pom.contains("<name>Fixture Library</name>"));
        assertTrue(pom.contains("<description>Fixture publication description.</description>"));
        assertTrue(pom.contains("<url>https://github.com/brainage04/publishing-fixture</url>"));
        assertTrue(pom.contains("<name>MIT License</name>"));
        assertTrue(pom.contains("<id>brainage04</id>"));
        assertTrue(pom.contains(
                "<connection>scm:git:https://github.com/brainage04/publishing-fixture.git</connection>"
        ));
        assertTrue(pom.contains(
                "<developerConnection>scm:git:ssh://git@github.com/brainage04/publishing-fixture.git</developerConnection>"
        ));
    }

    @Test
    void centralPublicationFailsBeforeNetworkWhenCredentialsAreMissing() throws IOException {
        writePublicationFixture();

        BuildResult result = runGradleAndFail("publishToMavenCentral");

        assertTrue(
                result.getOutput().contains(
                        "Set CENTRAL_PORTAL_USERNAME and CENTRAL_PORTAL_PASSWORD before publishing to Maven Central."
                ),
                result.getOutput()
        );
        assertFalse(result.getOutput().contains("Could not PUT"));
    }

    @Test
    void invalidPublishingTypeFailsBeforeCredentialsOrNetwork() throws IOException {
        writePublicationFixture();

        BuildResult result = runGradleAndFail(
                "publishToMavenCentral",
                "-PcentralPublishingType=unsupported"
        );

        assertTrue(
                result.getOutput().contains(
                        "Unsupported centralPublishingType 'unsupported'. Use one of: user_managed, automatic, portal_api."
                ),
                result.getOutput()
        );
        assertFalse(result.getOutput().contains("Set CENTRAL_PORTAL_USERNAME"));
        assertFalse(result.getOutput().contains("Could not PUT"));
    }

    @Test
    void gpgAgentModeDoesNotRequireInMemorySigningSecrets() throws IOException {
        writePublicationFixture();
        Map<String, String> environment = environmentWithCentralCredentials();

        BuildResult result = runner("validateMavenCentralPublishing", "-PuseGpgAgentSigning=true")
                .withEnvironment(environment)
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":validateMavenCentralPublishing").getOutcome());
    }

    @Test
    void inMemorySigningModeAcceptsCiSecretInputs() throws IOException {
        writePublicationFixture();
        Map<String, String> environment = environmentWithCentralCredentials();
        environment.put("SIGNING_KEY", "test-only-key-material");
        environment.put("SIGNING_PASSWORD", "test-only-password");

        BuildResult result = runner("validateMavenCentralPublishing")
                .withEnvironment(environment)
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":validateMavenCentralPublishing").getOutcome());
    }

    @Test
    void refusesToFinalizeWhenNoPublicationWasStaged() throws IOException {
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id '%s'
                }

                group = 'io.github.brainage04'
                version = '1.2.3'

                mavenCentralPublishing {
                    repository.set('brainage04/publishing-fixture')
                    publicationName.set('Fixture Library')
                    description.set('Fixture publication description.')
                }
                """.formatted(PLUGIN_ID));

        BuildResult result = runner("publishToMavenCentral", "-PuseGpgAgentSigning=true")
                .withEnvironment(environmentWithCentralCredentials())
                .buildAndFail();

        assertTrue(result.getOutput().contains(
                "No Maven publication was uploaded to the Central staging repository"
        ));
        assertFalse(result.getOutput().contains("Central upload request failed"));
    }

    @Test
    void preservesPluginMarkerNameAndDescription() throws IOException {
        writePluginPublicationFixture();

        BuildResult result = runGradle("publishAllPublicationsToLocalRepository");

        assertEquals(TaskOutcome.SUCCESS, result.task(":publishAllPublicationsToLocalRepository").getOutcome());
        Path markerPom = projectDirectory.resolve(
                "build/local-repo/com/example/fixture/com.example.fixture.gradle.plugin/1.2.3/"
                        + "com.example.fixture.gradle.plugin-1.2.3.pom"
        );
        assertTrue(Files.isRegularFile(markerPom));
        String markerMetadata = Files.readString(markerPom);
        assertTrue(markerMetadata.contains("<name>Fixture marker plugin</name>"));
        assertTrue(markerMetadata.contains("<description>Marker-specific description.</description>"));

        Path implementationPom = projectDirectory.resolve(
                "build/local-repo/io/github/brainage04/publishing-fixture/1.2.3/publishing-fixture-1.2.3.pom"
        );
        assertTrue(Files.isRegularFile(implementationPom));
        String implementationMetadata = Files.readString(implementationPom);
        assertTrue(implementationMetadata.contains("<name>Fixture Library</name>"));
        assertTrue(implementationMetadata.contains("<description>Fixture publication description.</description>"));
    }

    private void writePublicationFixture() throws IOException {
        Path source = projectDirectory.resolve("src/main/java/example/Fixture.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; public final class Fixture {}\n");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'java-library'
                    id '%s'
                }

                group = 'io.github.brainage04'
                version = '1.2.3'

                mavenCentralPublishing {
                    repository.set('brainage04/publishing-fixture')
                    publicationName.set('Fixture Library')
                    description.set('Fixture publication description.')
                }

                publishing {
                    publications {
                        mavenJava(MavenPublication) {
                            artifactId = 'fixture-library'
                            from components.java
                        }
                    }
                }
                """.formatted(PLUGIN_ID));
    }

    private void writePluginPublicationFixture() throws IOException {
        Path source = projectDirectory.resolve("src/main/java/example/FixturePlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                import org.gradle.api.Plugin;
                import org.gradle.api.Project;

                public final class FixturePlugin implements Plugin<Project> {
                    @Override
                    public void apply(Project project) {
                    }
                }
                """);
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'java-gradle-plugin'
                    id '%s'
                }

                group = 'io.github.brainage04'
                version = '1.2.3'

                gradlePlugin {
                    plugins {
                        fixture {
                            id = 'com.example.fixture'
                            implementationClass = 'example.FixturePlugin'
                            displayName = 'Fixture marker plugin'
                            description = 'Marker-specific description.'
                        }
                    }
                }

                mavenCentralPublishing {
                    repository.set('brainage04/publishing-fixture')
                    publicationName.set('Fixture Library')
                    description.set('Fixture publication description.')
                }
                """.formatted(PLUGIN_ID));
    }

    private Map<String, String> environmentWithCentralCredentials() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("CENTRAL_PORTAL_USERNAME", "test-only-username");
        environment.put("CENTRAL_PORTAL_PASSWORD", "test-only-password");
        environment.remove("SIGNING_KEY");
        environment.remove("SIGNING_PASSWORD");
        return environment;
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
