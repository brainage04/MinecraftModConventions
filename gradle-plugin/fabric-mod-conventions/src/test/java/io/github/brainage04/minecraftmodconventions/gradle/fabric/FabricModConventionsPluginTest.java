package io.github.brainage04.minecraftmodconventions.gradle.fabric;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricModConventionsPluginTest {
    private static final String BASE_PLUGIN_ID = "io.github.brainage04.fabric-mod-conventions";

    @TempDir
    Path projectDir;

    @Test
    void appliesSharedDefaultsAndAdditionalResourceProperties() throws IOException {
        writeFixture("25", true, """
                plugins {
                    id '%s'
                }


                repositories {
                    maven {
                        name = 'FixtureLocal'
                        url = uri('fixture-repo')
                    }
                }


                fabricModConventions {
                    additionalFabricModJsonProperties.add('fixture_dependency_version')
                }

                tasks.register('verifyBaseConventions') {
                    dependsOn tasks.named('processResources')
                    dependsOn tasks.named('sourcesJar')
                    dependsOn tasks.named('jar')

                    doLast {
                        def repositoryUrls = repositories
                                .findAll { it.hasProperty('url') }
                                .collect { it.url.toString() }
                        assert repositoryUrls.contains('https://repo.maven.apache.org/maven2/')
                        assert repositoryUrls.contains('https://libraries.minecraft.net')
                        assert repositoryUrls.contains('https://maven.fabricmc.net/')
                        assert project.version == '1.2.3'
                        assert project.group == 'io.github.brainage04.fixturemod'
                        assert base.archivesName.get() == 'fixturemod'
                        assert loom.areEnvironmentSourceSetsSplit()
                        assert sourceSets.findByName('client') != null
                        assert loom.mods.findByName('fixturemod') != null
                        assert loom.accessWidenerPath.get().asFile.name == 'fixturemod.accesswidener'
                        assert configurations.minecraft.dependencies.any {
                            it.group == 'com.mojang' && it.name == 'minecraft' && it.version == '26.2'
                        }
                        assert configurations.implementation.dependencies.any {
                            it.group == 'net.fabricmc' && it.name == 'fabric-loader' && it.version == '0.19.3'
                        }
                        assert configurations.implementation.dependencies.any {
                            it.group == 'net.fabricmc.fabric-api' && it.name == 'fabric-api' && it.version == '0.155.0+26.2'
                        }
                        assert configurations.testImplementation.dependencies.any {
                            it.group == 'net.fabricmc' && it.name == 'fabric-loader-junit' && it.version == '0.19.3'
                        }
                        assert tasks.named('test').get().systemProperties['fabric.side'] == 'server'
                        assert java.sourceCompatibility == JavaVersion.VERSION_25
                        assert java.targetCompatibility == JavaVersion.VERSION_25
                        assert tasks.named('compileJava').get().options.release.get() == 25
                        assert tasks.named('compileJava').get().options.encoding == 'UTF-8'

                        def jarFile = tasks.named('jar').get().archiveFile.get().asFile
                        assert zipTree(jarFile).matching { include 'LICENSE_fixturemod' }.files.size() == 1
                    }
                }
                """.formatted(BASE_PLUGIN_ID));

        BuildResult result = runGradle("verifyBaseConventions");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyBaseConventions").getOutcome());
        String expandedMetadata = Files.readString(projectDir.resolve("build/resources/main/fabric.mod.json"));
        assertTrue(expandedMetadata.contains("\"id\": \"fixturemod\""));
        assertTrue(expandedMetadata.contains("\"version\": \"1.2.3\""));
        assertTrue(expandedMetadata.contains("\"fixtureDependency\": \"9.8.7\""));
        assertFalse(expandedMetadata.contains("${"));
    }

    @Test
    void featureOptOutsPreserveConsumerConfiguration() throws IOException {
        writeFixture("24", false, """
                plugins {
                    id '%s'
                }

                version = '1.2.3'

                base {
                    archivesName = 'fixturemod'
                }

                repositories {
                    mavenCentral()
                    maven {
                        name = 'ManualMinecraft'
                        url = 'https://libraries.minecraft.net'
                    }
                    maven {
                        name = 'ManualFabric'
                        url = 'https://maven.fabricmc.net/'
                    }
                }

                dependencies {
                    minecraft 'com.mojang:minecraft:26.2'
                    implementation 'net.fabricmc:fabric-loader:0.19.3'
                }

                fabricModConventions {
                    repositoriesEnabled = false
                    javaEnabled = false
                    sourcesJarEnabled = false
                    resourceExpansionEnabled = false
                    licenseJarEnabled = false
                }

                java {
                    sourceCompatibility = JavaVersion.VERSION_25
                    targetCompatibility = JavaVersion.VERSION_25
                }

                tasks.withType(JavaCompile).configureEach {
                    options.release = 25
                }

                tasks.register('verifyOptOuts') {
                    dependsOn tasks.named('processResources')
                    dependsOn tasks.named('jar')

                    doLast {
                        assert repositories.findByName('MinecraftLibraries') == null
                        assert java.sourceCompatibility == JavaVersion.VERSION_25
                        assert java.targetCompatibility == JavaVersion.VERSION_25
                        assert tasks.named('compileJava').get().options.release.get() == 25
                        assert tasks.findByName('sourcesJar') == null

                        def jarFile = tasks.named('jar').get().archiveFile.get().asFile
                        assert zipTree(jarFile).matching { include 'LICENSE_fixturemod' }.files.isEmpty()
                    }
                }
                """.formatted(BASE_PLUGIN_ID));

        BuildResult result = runGradle("verifyOptOuts");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOptOuts").getOutcome());
        String metadata = Files.readString(projectDir.resolve("build/resources/main/fabric.mod.json"));
        assertTrue(metadata.contains("${mod_id}"));
        assertTrue(metadata.contains("${fixture_dependency_version}"));
    }

    @Test
    void rejectsUnknownModSide() throws IOException {
        writeFixture("25", false, """
                plugins {
                    id '%s'
                }
                """.formatted(BASE_PLUGIN_ID));
        Path properties = projectDir.resolve("gradle.properties");
        Files.writeString(properties, Files.readString(properties).replace("mod_side=both", "mod_side=desktop"));

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("help")
                .buildAndFail();

        assertTrue(result.getOutput().contains(
                "Project property 'mod_side' must be one of both, client, or server, but was 'desktop'."
        ));
    }

    private BuildResult runGradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private void writeFixture(String javaVersion, boolean canonical, String buildScript) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'fabric-mod-conventions-fixture'\n");
        Files.writeString(projectDir.resolve("gradle.properties"), """
                mod_side=both
                mod_id=fixturemod
                mod_name=Fixture Mod
                mod_version=1.2.3
                maven_group=io.github.brainage04.fixturemod
                archives_base_name=fixturemod
                loader_version=0.19.3
                minecraft_version=26.2
                fabric_api_version=0.155.0+26.2
                java_version=%s
                fixture_dependency_version=9.8.7
                """.formatted(javaVersion));
        Files.writeString(projectDir.resolve("build.gradle"), buildScript);
        Files.writeString(projectDir.resolve("LICENSE"), "Fixture license\n");
        if (canonical) {
            Path accessWidener = projectDir.resolve("src/main/resources/fixturemod.accesswidener");
            Files.createDirectories(accessWidener.getParent());
            Files.writeString(accessWidener, "accessWidener v2 official\n");
        }

        Path javaSource = projectDir.resolve("src/main/java/example/Fixture.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, "package example; public final class Fixture {}\n");

        Path fabricMetadata = projectDir.resolve("src/main/resources/fabric.mod.json");
        Files.createDirectories(fabricMetadata.getParent());
        Files.writeString(fabricMetadata, """
                {
                  "id": "${mod_id}",
                  "version": "${mod_version}",
                  "name": "${mod_name}",
                  "loader": "${loader_version}",
                  "minecraft": "${minecraft_version}",
                  "java": "${java_version}",
                  "fixtureDependency": "${fixture_dependency_version}"
                }
                """);
    }
}
