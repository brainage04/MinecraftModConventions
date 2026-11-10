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

    @Test
    void vanillaIdentityCheckWarnsAboutTheAutoSleepGateAndPassesDataDrivenOrOptedOutCode() throws IOException {
        writeFixture("25", false, """
                plugins {
                    id '%s'
                }

                tasks.register('verifyVanillaIdentityWiring') {
                    doLast {
                        assert tasks.check.taskDependencies.getDependencies(tasks.check)*.name.contains('checkVanillaIdentity')
                    }
                }
                """.formatted(BASE_PLUGIN_ID));
        Path gameTest = projectDir.resolve("src/gametest/java/example/NetherBedGameTest.java");
        Files.createDirectories(gameTest.getParent());
        Files.writeString(gameTest, """
                package example;
                final class NetherBedGameTest {
                    static boolean nether(net.minecraft.world.level.Level level) {
                        return level.dimension() == net.minecraft.world.level.Level.NETHER;
                    }
                }
                """);
        Path sleeper = projectDir.resolve("src/client/java/example/AutoSleeper.java");
        Files.createDirectories(sleeper.getParent());
        Path report = projectDir.resolve("build/reports/vanilla-identity.txt");
        String gate = """
                package example;
                import net.minecraft.world.level.Level;
                final class AutoSleeper {
                    static void tick(Level level) {
                %s
                    }
                }
                """;
        String hit = "src/client/java/example/AutoSleeper.java:6: if (level.dimension() != Level.OVERWORLD) return;";

        Files.writeString(sleeper, gate.formatted("""
                        // Beds explode in the Nether and the End.
                        if (level.dimension() != Level.OVERWORLD) return;"""));
        BuildResult warned = runGradle("checkVanillaIdentity", "verifyVanillaIdentityWiring");
        String output = warned.getOutput();
        assertEquals(TaskOutcome.SUCCESS, warned.task(":checkVanillaIdentity").getOutcome(), output);
        assertEquals(TaskOutcome.SUCCESS, warned.task(":verifyVanillaIdentityWiring").getOutcome(), output);
        assertTrue(output.contains(hit), output);
        assertTrue(output.contains("EnvironmentAttributes.BED_RULE"), output);
        assertTrue(output.contains("EnvironmentAttribute.isSyncable()"), output);
        assertTrue(output.contains("place"), output);
        assertFalse(output.contains("NetherBedGameTest"), output);
        assertTrue(Files.readString(report).contains(hit), Files.readString(report));

        Files.writeString(sleeper, gate.formatted("""
                        if (level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos).explodes()) return;"""));
        BuildResult dataDriven = runGradle("checkVanillaIdentity");
        assertEquals(TaskOutcome.SUCCESS, dataDriven.task(":checkVanillaIdentity").getOutcome(), dataDriven.getOutput());
        assertFalse(dataDriven.getOutput().contains("AutoSleeper.java"), dataDriven.getOutput());
        assertEquals("", Files.readString(report));

        Files.writeString(sleeper, gate.formatted("""
                        // vanilla-identity: BED_RULE is not synced to the client, so a client-only mod falls back to vanilla
                        if (level.dimension() != Level.OVERWORLD) return;"""));
        BuildResult optedOut = runGradle("checkVanillaIdentity");
        assertEquals(TaskOutcome.SUCCESS, optedOut.task(":checkVanillaIdentity").getOutcome(), optedOut.getOutput());
        assertFalse(optedOut.getOutput().contains("AutoSleeper.java"), optedOut.getOutput());

        Files.writeString(sleeper, gate.formatted("""
                        // vanilla-identity:
                        if (level.dimension() != Level.OVERWORLD) return;"""));
        BuildResult reasonless = runGradle("checkVanillaIdentity");
        String reasonlessOutput = reasonless.getOutput();
        assertEquals(TaskOutcome.SUCCESS, reasonless.task(":checkVanillaIdentity").getOutcome(), reasonlessOutput);
        assertTrue(reasonlessOutput.contains("needs a reason"), reasonlessOutput);
        assertTrue(reasonlessOutput.contains(hit), reasonlessOutput);
    }

    @Test
    void vanillaTagAndWorldHeightChecksWarnAboutComparisonsAndPassPlacedBlocks() throws IOException {
        writeFixture("25", false, """
                plugins {
                    id '%s'
                }

                tasks.register('verifyQualityChecks') {
                    doLast {
                        ['checkVanillaIdentity', 'checkVanillaTagIdentities', 'checkWorldHeight'].each { name ->
                            assert tasks.check.taskDependencies.getDependencies(tasks.check)*.name.contains(name)
                        }
                    }
                }
                """.formatted(BASE_PLUGIN_ID));
        write("src/client/java/example/BedCheck.java", """
                package example;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.item.Items;
                import net.minecraft.world.level.block.Blocks;
                final class BedCheck {
                    static boolean beds(Object block) {
                        return block == Blocks.RED_BED;
                    }

                    static boolean swords(Item item) {
                        return item == Items.DIAMOND_SWORD;
                    }
                }
                """);
        write("src/client/java/example/PlacedBed.java", """
                package example;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.Level;
                import net.minecraft.world.level.block.Blocks;
                final class PlacedBed {
                    static void place(Level level, BlockPos pos) {
                        level.setBlock(pos, Blocks.RED_BED.defaultBlockState(), 3);
                    }
                }
                """);
        write("src/main/java/example/TopOfWorld.java", """
                package example;
                import net.minecraft.core.BlockPos;
                final class TopOfWorld {
                    static boolean outside(int y, int x, int z) {
                        if (y < -64) return true;
                        return new BlockPos(x, 320, z).getY() > 0;
                    }

                    static int width() {
                        return 256;
                    }
                }
                """);

        BuildResult result = runGradle("checkVanillaTagIdentities", "checkWorldHeight", "verifyQualityChecks");

        String output = result.getOutput();
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkVanillaTagIdentities").getOutcome(), output);
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkWorldHeight").getOutcome(), output);
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyQualityChecks").getOutcome(), output);
        assertTrue(output.contains("src/client/java/example/BedCheck.java:7: return block == Blocks.RED_BED;"), output);
        assertTrue(output.contains("src/client/java/example/BedCheck.java:11: return item == Items.DIAMOND_SWORD;"),
                output);
        assertTrue(output.contains("block tag #minecraft:beds"), output);
        assertFalse(output.contains("PlacedBed"), output);
        assertTrue(output.contains("if (y < -64) return true;"), output);
        assertTrue(output.contains("new BlockPos(x, 320, z)"), output);
        assertFalse(output.contains("return 256;"), output);
        String tagsReport = Files.readString(projectDir.resolve("build/reports/vanilla-tag-identities.txt"));
        assertTrue(tagsReport.contains("BedCheck.java:7"), tagsReport);
        assertTrue(tagsReport.contains("block tag #minecraft:beds"), tagsReport);
        assertTrue(tagsReport.contains("BedCheck.java:11"), tagsReport);
        assertTrue(tagsReport.contains("item tag #minecraft:swords"), tagsReport);
        assertFalse(tagsReport.contains("PlacedBed"), tagsReport);
        String heightReport = Files.readString(projectDir.resolve("build/reports/world-height.txt"));
        assertTrue(heightReport.contains("if (y < -64) return true;"), heightReport);
        assertTrue(heightReport.contains("new BlockPos(x, 320, z)"), heightReport);
        assertFalse(heightReport.contains("return 256;"), heightReport);
    }

    private void write(String path, String content) throws IOException {
        Path file = projectDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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
