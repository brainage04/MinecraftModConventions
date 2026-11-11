package io.github.brainage04.minecraftmodconventions.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginComponentIsolationTest {
    private static final String BASE_PLUGIN_ID = "io.github.brainage04.fabric-mod-conventions";
    private static final String RECORDER_PLUGIN_ID = "io.github.brainage04.client-gametest-recorder";
    private static final String PRODUCTION_PLUGIN_ID = "io.github.brainage04.production-gametests";
    private static final String INTEGRATION_PLUGIN_ID = "io.github.brainage04.integration-gametests";
    private static final String WORKSPACE_PLUGIN_ID = "io.github.brainage04.workspace-dependencies";
    private static final String QUALITY_PLUGIN_ID = "io.github.brainage04.java-quality-conventions";

    @TempDir
    Path projectDir;

    @Test
    void basePluginAppliesOnlyLoom() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID, """
                tasks.register('verifyBaseIsolation') {
                    doLast {
                        assert project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.extensions.findByName('clientGameTestRecorder') == null
                        assert project.extensions.findByName('productionGameTests') == null
                        assert project.tasks.findByName('prepareClientGameTestRun') == null
                        assert project.tasks.findByName('runProductionClientGameTest') == null
                    }
                }
                """);

        var result = runGradle("verifyBaseIsolation");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyBaseIsolation").getOutcome());
    }

    @Test
    void recorderPluginOwnsOnlyRecorderFeatures() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + RECORDER_PLUGIN_ID, """
                tasks.register('verifyRecorderIsolation') {
                    doLast {
                        assert project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.extensions.findByName('clientGameTestRecorder') != null
                        assert project.extensions.findByName('productionGameTests') == null
                        assert project.tasks.findByName('prepareClientGameTestRun') != null
                        assert project.tasks.findByName('recordClientGameTest') != null
                        assert project.tasks.findByName('runProductionClientGameTest') == null
                    }
                }
                """);

        var result = runGradle("verifyRecorderIsolation");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyRecorderIsolation").getOutcome());
    }

    @Test
    void productionPluginIsStandaloneAndRegistersEnabledTasks() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """

                productionGameTests {
                    includeFabricApiDependency = false
                    runtimeLibraryDependencies.add("example:runtime-library:1.0")
                }

                tasks.register('verifyProductionIsolation') {
                    doLast {
                        assert project.extensions.findByName('clientGameTestRecorder') == null
                        assert project.extensions.findByName('productionGameTests') != null
                        assert project.sourceSets.findByName('gametest') != null
                        assert project.loom.mods.findByName('fixturemod-gametest') != null
                        assert project.tasks.findByName('runGameTest') != null
                        assert project.tasks.findByName('runClientGameTest') != null
                        assert project.tasks.findByName('prepareClientGameTestRun') == null
                        assert project.tasks.findByName('runProductionClientGameTest') != null
                        assert project.tasks.findByName('runProductionServerGameTest') != null
                        assert project.tasks.findByName('runAllProductionGameTests') != null
                        assert project.tasks.findByName('productionGameTestJar') instanceof org.gradle.jvm.tasks.Jar
                        assert project.tasks.findByName('prepareProductionGameTestRuns') instanceof io.github.brainage04.minecraftmodconventions.gradle.production.PrepareProductionGameTestRunsTask
                        assert project.configurations.productionGameTestRuntimeLibraries.dependencies.any {
                            it.group == 'example' && it.name == 'runtime-library' && it.version == '1.0'
                        }
                        assert project.tasks.named('runProductionClientGameTest').get().mods.files.any {
                            it.name.endsWith('-production-gametest.jar')
                        }
                        assert project.tasks.named('runProductionServerGameTest').get().mods.files.any {
                            it.name.endsWith('-production-gametest.jar')
                        }
                        assert project.tasks.named('runProductionClientGameTest').get().jvmArgs.get()
                                .contains('-Dminecraftmodconventions.clientGameTest=true')
                    }
                }
                """);

        Path gameTestDescriptor = projectDir.resolve("src/gametest/resources/fabric.mod.json");
        Files.createDirectories(gameTestDescriptor.getParent());
        Files.writeString(gameTestDescriptor, "{\"id\":\"${mod_id}-gametest\",\"version\":\"${mod_version}\"}");

        var result = runGradle(
                "prepareProductionGameTestRuns",
                "productionGameTestJar",
                "verifyProductionIsolation"
        );

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyProductionIsolation").getOutcome());
        assertEquals(
                "eula=true\n",
                Files.readString(projectDir.resolve("build/run/productionClientGameTest/eula.txt"))
        );
        assertEquals(
                "eula=true\n",
                Files.readString(projectDir.resolve("build/run/productionServerGameTest/eula.txt"))
        );
        try (var files = Files.list(projectDir.resolve("build/libs"))) {
            Path gameTestJar = files
                    .filter(path -> path.getFileName().toString().endsWith("-production-gametest.jar"))
                    .findFirst()
                    .orElseThrow();
            try (var jar = new JarFile(gameTestJar.toFile())) {
                assertEquals(
                        "{\"id\":\"fixturemod-gametest\",\"version\":\"1.2.3\"}",
                        new String(
                                jar.getInputStream(jar.getJarEntry("fabric.mod.json")).readAllBytes(),
                                StandardCharsets.UTF_8
                        )
                );
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"runGameTest", "runProductionServerGameTest"})
    void fabricServerGameTestsDeleteOnlyTheirConfiguredWorldBeforeLaunch(String taskName) throws IOException {
        boolean production = taskName.equals("runProductionServerGameTest");
        String plugins = production ? BASE_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID : BASE_PLUGIN_ID;
        String gameTests = production ? """
                productionGameTests { includeFabricApiDependency = false }
                """ : """
                fabricApi.configureTests {
                    createSourceSet = true
                    modId = 'fixturemod-gametest'
                    enableGameTests = true
                    enableClientGameTests = false
                }
                """;
        writeLoomFixture(plugins, gameTests + """
                gradle.projectsEvaluated {
                    def runTask = tasks.named('%s').get()
                    %s
                    tasks.register('verifyFreshGameTestWorld') {
                        doLast {
                            def world = file('custom/gameTest/world')
                            assert new File(world, 'entities/stale.marker').isFile()
                            2.times {
                                // Exercise the named reset only; inherited task actions can launch Minecraft.
                                runTask.actions.findAll { it.displayName.endsWith('resetGameTestWorld') }.each { action ->
                                    assert action == runTask.actions.first() : 'World reset must precede server launch'
                                    action.execute(runTask)
                                }
                                assert !world.exists() : "Saved world survived ${runTask.path}"
                                if (it == 0) {
                                    new File(world, 'entities').mkdirs()
                                    new File(world, 'entities/stale.marker').text = 'interrupted rerun'
                                }
                            }
                        }
                    }
                }
                """.formatted(taskName, production
                ? "runTask.runDir.set(layout.projectDirectory.dir('custom/gameTest'))"
                : "loom.runs.gameTest.runDir = 'custom/gameTest'"));

        Path runDir = projectDir.resolve("custom/gameTest");
        Files.createDirectories(runDir.resolve("world/entities"));
        Files.writeString(runDir.resolve("world/entities/stale.marker"), "interrupted run");
        for (String path : new String[] {"logs/keep.log", "options.txt", "eula.txt", "server.properties"}) {
            Path file = runDir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "keep " + path);
        }
        Path otherWorld = projectDir.resolve("run/world/keep.marker");
        Files.createDirectories(otherWorld.getParent());
        Files.writeString(otherWorld, "ordinary server world");

        BuildResult result = runGradle("verifyFreshGameTestWorld");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyFreshGameTestWorld").getOutcome());
        assertFalse(Files.exists(runDir.resolve("world")));
        for (String path : new String[] {"logs/keep.log", "options.txt", "eula.txt", "server.properties"}) {
            assertEquals("keep " + path, Files.readString(runDir.resolve(path)));
        }
        assertEquals("ordinary server world", Files.readString(otherWorld));
    }

    @Test
    void integrationPluginAddsSuiteRunsToASingleLoaderFabricProject() throws IOException {
        // Applied before production-gametests: the suite's production run still sees the ordinary one and joins
        // the single-loader aggregate.
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + INTEGRATION_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """
                productionGameTests { includeFabricApiDependency = false }

                integrationGameTests {
                    compat {
                        filter = 'fixturemod:compat_*'
                    }
                }

                tasks.register('verifyStandaloneIntegrationGameTests') {
                    doLast {
                        assert sourceSets.compat.java.srcDirs.contains(file('src/compat/java'))
                        assert configurations.compatMods.transitive == false
                        // Fabric Loom keeps run properties as JVM arguments; Architectury Loom as system properties.
                        assert loom.runs.compatGameTest.vmArgs.contains('-Dfabric-api.gametest.filter=fixturemod:compat_*')
                        assert tasks.runCompatGameTest.actions.first().displayName.endsWith('resetGameTestWorld')
                        def production = tasks.runProductionCompatGameTest
                        assert production.jvmArgs.get().contains('-Dfabric-api.gametest.filter=fixturemod:compat_*')
                        assert production.runDir.get().asFile == file('build/run/productionCompatGameTest')
                        assert production.mods.files*.name.containsAll(
                                ['fixturemod-1.2.3-compat-gametest.jar', 'fixturemod-1.2.3-production-gametest.jar'])
                        assert !tasks.runProductionServerGameTest.mods.files*.name.contains('fixturemod-1.2.3-compat-gametest.jar')
                        assert tasks.runAllProductionGameTests.taskDependencies.getDependencies(null)*.name
                                .containsAll(['runProductionServerGameTest', 'runProductionCompatGameTest'])
                    }
                }
                """);

        assertEquals(TaskOutcome.SUCCESS, runGradle("verifyStandaloneIntegrationGameTests")
                .task(":verifyStandaloneIntegrationGameTests").getOutcome());
    }

    @Test
    void integrationGameTestSuiteWithoutAFilterFailsConfiguration() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID + "'\n    id '" + INTEGRATION_PLUGIN_ID, """
                productionGameTests { includeFabricApiDependency = false }
                integrationGameTests { compat }
                """);

        String output = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("help")
                .buildAndFail()
                .getOutput();
        assertTrue(output.contains("integrationGameTests.compat.filter is required"), output);
    }


    @Test
    void workspacePluginAddsCentralWithoutLoomOrFeatureTasks() throws IOException {
        writeFixture(WORKSPACE_PLUGIN_ID, false, """
                tasks.register('verifyWorkspaceRepository') {
                    doLast {
                        assert project.repositories.any { it.hasProperty('url') && it.url.toString().contains('repo.maven.apache.org/maven2') }
                        assert !project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.tasks.findByName('prepareClientGameTestRun') == null
                        assert project.tasks.findByName('runProductionClientGameTest') == null
                    }
                }
                """);

        var result = runGradle("verifyWorkspaceRepository");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyWorkspaceRepository").getOutcome());
    }

    @Test
    void qualityChecksAreStagedByDefault() throws IOException {
        writeFixture(QUALITY_PLUGIN_ID, false, "apply plugin: 'java'\nrepositories { mavenCentral() }");
        Path source = projectDir.resolve("src/main/java/BadlyFormatted.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "final class BadlyFormatted{}");

        var result = runGradle("check");

        assertEquals(TaskOutcome.SKIPPED, result.task(":spotlessJavaCheck").getOutcome());
        assertEquals(TaskOutcome.SKIPPED, result.task(":checkstyleMain").getOutcome());
    }



    @Test
    void leafPluginsApplySharedBaseWithoutDuplicates() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + RECORDER_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """
                tasks.register('verifyIdempotentComponents') {
                    doLast {
                        assert project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.extensions.findByName('clientGameTestRecorder') != null
                        assert project.extensions.findByName('productionGameTests') != null
                        assert project.tasks.findAll { it.name == 'prepareClientGameTestRun' }.size() == 1
                        assert project.tasks.findAll { it.name == 'recordClientGameTest' }.size() == 1
                        assert project.configurations.gametestImplementation.dependencies.any {
                            it.group == 'io.github.brainage04'
                                    && it.name == 'minecraftmodconventions'
                                    && it.version == 'fixture-version'
                        }
                        assert project.configurations.productionRuntimeMods.dependencies.any {
                            it.group == 'io.github.brainage04'
                                    && it.name == 'minecraftmodconventions'
                                    && it.version == 'fixture-version'
                        }
                    }
                }
                """);

        var result = runGradle("verifyIdempotentComponents");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyIdempotentComponents").getOutcome());
    }

    @Test
    void automatedClientsGetTheTestProfileAndIgnoreTheDevClientOptions() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + RECORDER_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """
                productionGameTests { includeFabricApiDependency = false }

                clientGameTestRecorder {
                    disableUnsecureChatToast = false
                    disableSocialInteractionsToast = false
                    disableRecipeToasts = false
                    disableAdvancementToasts = false
                    disableAdvancementChatMessages = false
                }

                tasks.register('verifyRecorderNotificationSettings') {
                    doLast {
                        def recorder = project.extensions.clientGameTestRecorder
                        assert !recorder.disableUnsecureChatToast.get()
                        assert !recorder.disableSocialInteractionsToast.get()
                        assert !recorder.disableRecipeToasts.get()
                        assert !recorder.disableAdvancementToasts.get()
                        assert !recorder.disableAdvancementChatMessages.get()
                    }
                }
                """);
        Path devOptions = projectDir.resolve("launcher/options.txt");
        Files.createDirectories(devOptions.getParent());
        Files.writeString(devOptions, "guiScale:4\nrenderDistance:32\n");

        // A run that brings its own display but no recorder sink, as some CI jobs do, stays on the test profile.
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withEnvironment(java.util.Map.of("GTR_RECORDING_MANAGED_XVFB", "true"))
                .withArguments(
                        "prepareClientGameTestRun",
                        "prepareProductionGameTestRuns",
                        "verifyRecorderNotificationSettings",
                        "-Pminecraftmodconventions.devClientOptions=" + devOptions
                )
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareClientGameTestRun").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyRecorderNotificationSettings").getOutcome());
        assertEquals(testOptions(false), Files.readString(projectDir.resolve("build/run/clientGameTest/options.txt")));
        assertEquals(testOptions(false),
                Files.readString(projectDir.resolve("build/run/productionClientGameTest/options.txt")));
    }

    @Test
    void recordedClientsGetTheShowcaseProfileOnTheRecorderSink() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + RECORDER_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """
                productionGameTests { includeFabricApiDependency = false }
                """);

        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withEnvironment(java.util.Map.of(
                        "GTR_RECORDING_MANAGED_XVFB", "true",
                        "GTR_RECORDING_AUDIO_DEVICE", "gametest_recorder_fixture"
                ))
                .withArguments("prepareClientGameTestRun", "prepareProductionGameTestRuns")
                .build();

        String expected = testOptions(true)
                .replace("fullscreen:false", "fullscreen:true")
                .replace("renderDistance:5", "renderDistance:12")
                .replace("graphicsPreset:\"fast\"", "graphicsPreset:\"fancy\"")
                .replace("ao:false", "ao:true")
                .replace("cutoutLeaves:false", "cutoutLeaves:true")
                .replace("entityShadows:false", "entityShadows:true")
                .replace("soundDevice:\"\"", "soundDevice:\"gametest_recorder_fixture\"")
                .replace("soundCategory_master:0.0", "soundCategory_master:0.7")
                .replaceAll("(soundCategory_(?!master|music)[a-z]+):0\\.0", "$1:1.0");
        assertEquals(expected, Files.readString(projectDir.resolve("build/run/clientGameTest/options.txt")));
        assertEquals(expected, Files.readString(projectDir.resolve("build/run/productionClientGameTest/options.txt")));
    }

    @Test
    void bothClientLaunchesGetAStartupDeadline() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID + "'\n    id '" + RECORDER_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """
                productionGameTests { includeFabricApiDependency = false }

                // Loom resolves the development run's classpath to list its JVM arguments; the runtime helper
                // comes from this build's local repository.
                repositories { maven { url = uri('%s') } }

                tasks.register('verifyStartupDeadline') {
                    doLast {
                        def ready = '-Dminecraftmodconventions.clientReadyFile='
                        assert tasks.runProductionClientGameTest.jvmArgs.get().contains(ready
                                + file('build/minecraftmodconventions/client-ready/runProductionClientGameTest'))
                        assert tasks.runClientGameTest.jvmArgs.contains(ready
                                + file('build/minecraftmodconventions/client-ready/runClientGameTest'))
                        assert clientGameTestRecorder.clientStartupTimeoutSeconds.get() == 42
                        [tasks.runProductionClientGameTest, tasks.runClientGameTest].each {
                            assert it.actions.size() == 1 : "${it.path} launches outside its deadline"
                        }
                    }
                }
                """.formatted(Path.of(System.getProperty("pluginTestRepository")).toUri()));
        Path properties = projectDir.resolve("gradle.properties");
        Files.writeString(properties, Files.readString(properties)
                .replace("=fixture-version", "=" + System.getProperty("pluginTestVersion")));

        var result = runGradle("verifyStartupDeadline", "-Pminecraftmodconventions.clientStartupTimeoutSeconds=42");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStartupDeadline").getOutcome());
    }

    private BuildResult runGradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private void writeLoomFixture(String pluginIds, String configuration) throws IOException {
        writeFixture(pluginIds, true, configuration);
    }

    private void writeFixture(String pluginIds, boolean includeLoomDependencies, String configuration) throws IOException {
        String repositories = "";
        String dependencies = "";
        String properties = includeLoomDependencies
                ? """
                mod_side=both
                mod_id=fixturemod
                mod_name=Fixture Mod
                mod_version=1.2.3
                maven_group=io.github.brainage04.fixture
                archives_base_name=fixturemod
                minecraft_version=26.2
                loader_version=0.19.3
                fabric_api_version=0.155.0+26.2
                minecraftmodconventions_version=fixture-version
                java_version=25
                """
                : "java_version=25\n";
        Files.writeString(projectDir.resolve("gradle.properties"), properties);
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'recorder-convention-fixture'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id '%s'
                }

                %s
                %s
                %s
                """.formatted(pluginIds, repositories, dependencies, configuration));
    }

    /** The test profile, written out literally so a change to it shows up here. */
    private static String testOptions(boolean joinedFirstServer) {
        return String.join("\n",
                "version:4903",
                "lang:en_us",
                "fullscreen:false",
                "overrideWidth:854",
                "overrideHeight:480",
                "guiScale:2",
                "renderDistance:5",
                "simulationDistance:5",
                "maxFps:30",
                "enableVsync:false",
                "inactivityFpsLimit:\"minimized\"",
                "graphicsPreset:\"fast\"",
                "ao:false",
                "cutoutLeaves:false",
                "entityShadows:false",
                "improvedTransparency:false",
                "renderClouds:\"false\"",
                "particles:2",
                "biomeBlendRadius:0",
                "chunkSectionFadeInTime:0.0",
                "entityDistanceScaling:0.5",
                "mipmapLevels:0",
                "maxAnisotropyBit:0",
                "menuBackgroundBlurriness:0",
                "prioritizeChunkUpdates:0",
                "weatherRadius:5",
                "soundDevice:\"\"",
                "soundCategory_master:0.0",
                "soundCategory_music:0.0",
                "soundCategory_record:0.0",
                "soundCategory_weather:0.0",
                "soundCategory_block:0.0",
                "soundCategory_hostile:0.0",
                "soundCategory_neutral:0.0",
                "soundCategory_player:0.0",
                "soundCategory_ambient:0.0",
                "soundCategory_voice:0.0",
                "soundCategory_ui:0.0",
                "narrator:0",
                "narratorHotkey:false",
                "tutorialStep:none",
                "onboardAccessibility:false",
                "joinedFirstServer:" + joinedFirstServer,
                "skipMultiplayerWarning:true",
                "realmsNotifications:false",
                "telemetryOptInExtra:false",
                "musicToast:\"never\"",
                "hideSplashTexts:true",
                "showAutosaveIndicator:false",
                "pauseOnLostFocus:false",
                "autoJump:false",
                "toggleSprint:false",
                "toggleCrouch:false",
                ""
        );
    }
}
