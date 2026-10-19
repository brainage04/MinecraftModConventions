package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModPublishingPluginTest {
    private static final String PLUGIN_ID = "io.github.brainage04.mod-publishing";

    @TempDir
    Path projectDirectory;

    @BeforeEach
    void createFixtureFiles() throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/resources/assets/publisher_fixture"));
        Files.createDirectories(projectDirectory.resolve(".modrinth"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'publisher-fixture'\n");
        Files.writeString(projectDirectory.resolve("gradle.properties"), """
                java_version=25
                minecraft_version=26.1.2
                """);
        Files.writeString(projectDirectory.resolve("payload.txt"), "exact release payload\n");
        Files.writeString(projectDirectory.resolve("README.md"), "# Publisher fixture\n\nProject body.\n");
        Files.writeString(projectDirectory.resolve("LICENSE"), "fixture license\n");
        Files.write(
                projectDirectory.resolve("src/main/resources/assets/publisher_fixture/icon.png"),
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}
        );
        Files.writeString(
                projectDirectory.resolve("src/main/resources/fabric.mod.json"),
                """
                        {
                          "schemaVersion": 1,
                          "id": "publisher_fixture",
                          "version": "1.2.3",
                          "name": "Publisher Fixture",
                          "description": "Fabric metadata description",
                          "environment": "*",
                          "entrypoints": { "client": ["example.Client"] },
                          "icon": "assets/publisher_fixture/icon.png",
                          "license": "MIT",
                          "contact": { "sources": "https://example.invalid/source" },
                          "depends": {
                            "minecraft": "*",
                            "fabricloader": "*",
                            "fzzy_config": "*"
                          },
                          "recommends": { "modmenu": "*" },
                          "conflicts": { "bad_mod": "*" }
                        }
                        """
        );
        Files.writeString(
                projectDirectory.resolve(".modrinth/project.json"),
                """
                        {
                          "slug": "publisher-fixture",
                          "categories": ["library"],
                          "dependency_overrides": {
                            "fzzy_config": { "project_slug": "fzzy-config" },
                            "modmenu": { "skip": true },
                            "bad_mod": { "project_id": "AbCdEf12", "dependency_type": "incompatible" }
                          },
                          "version": {
                            "game_versions": ["26.1.2"],
                            "loaders": ["fabric"],
                            "featured": false,
                            "dependencies": [
                              { "project_id": "ZyXwVu98", "dependency_type": "optional" }
                            ]
                          }
                        }
                        """
        );
    }

    @Test
    void appliesUpstreamPublisherWithoutEnablingDestinations() throws IOException {
        writeBuildFile("", """
                tasks.register('verifyPublisherIsolation') {
                    doLast {
                        assert pluginManager.hasPlugin('me.modmuss50.mod-publish-plugin')
                        assert !modPublishing.github.enabled.get()
                        assert !modPublishing.modrinth.enabled.get()
                        assert !modPublishing.curseforge.enabled.get()
                        assert tasks.findByName('validateModPublication') != null
                        assert tasks.findByName('prepareModrinthProjectMetadata') != null
                        assert tasks.findByName('syncModrinthProject') != null
                        assert tasks.findByName('syncModrinthIcon') != null
                        assert tasks.findByName('publishGithub') != null
                        assert tasks.findByName('publishModrinth') != null
                        assert tasks.findByName('publishCurseforge') != null
                    }
                }
                """);

        BuildResult result = runGradle("verifyPublisherIsolation");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyPublisherIsolation").getOutcome());
    }

    @Test
    void validationBuildsTheDefaultReleaseJar() throws IOException {
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id '%s'
                }

                version = '1.2.3'

                modPublishing {
                    version = '1.2.3'
                    releaseTag = 'v1.2.3'
                    displayName = 'Publisher Fixture 1.2.3'
                    changelog = 'Release notes'
                    dryRun = true
                    github {
                        repository = 'brainage04/publisher-fixture'
                        commitish = 'main'
                    }
                }
                """.formatted(PLUGIN_ID));

        BuildResult result = runGradle("validateModPublication");

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":validateModPublication").getOutcome());
    }

    @Test
    void rejectsMalformedPublishingBooleanInsteadOfSilentlyDisablingRelease() throws IOException {
        writeBuildFile("""
                github.repository = 'brainage04/publisher-fixture'
                github.commitish = 'main'
                """, "");

        BuildResult result = runGradleAndFail("publishGithub", "-PpublishGithub=tru");

        assertTrue(result.getOutput().contains("publishGithub must be either true or false"));
    }

    @Test
    void rejectsMalformedDryRunBooleanInsteadOfStartingLivePublication() throws IOException {
        writeBuildFile("""
                github {
                    repository = 'brainage04/publisher-fixture'
                    commitish = 'main'
                }
                """, "");

        BuildResult result = runGradleAndFail("publishGithub", "-PmodPublishingDryRun=tru");

        assertTrue(result.getOutput().contains("modPublishingDryRun must be either true or false"));
    }

    @Test
    void preservesConventionValuesWhenConsumersAddPublishingTargets() throws IOException {
        writeBuildFile("""
                minecraftVersions.add('1.21.11')
                modLoaders.add('quilt')
                """, """
                tasks.register('verifyPublishingCollections') {
                    doLast {
                        assert modPublishing.minecraftVersions.get() == ['26.1.2', '1.21.11']
                        assert modPublishing.modLoaders.get() == ['fabric', 'quilt']
                    }
                }
                """);

        BuildResult result = runGradle("verifyPublishingCollections");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyPublishingCollections").getOutcome());
    }

    @Test
    void rejectsNegativeRetryCountsDuringPublicationValidation() throws IOException {
        writeBuildFile("""
                dryRun = true
                maxRetries = -1
                github {
                    repository = 'brainage04/publisher-fixture'
                    commitish = 'main'
                }
                """, "");

        BuildResult result = runGradleAndFail("publishGithub");

        assertTrue(result.getOutput().contains("modPublishing.maxRetries must be zero or greater"));
        assertEquals(TaskOutcome.FAILED, result.task(":validateModPublication").getOutcome());
    }

    @Test
    void dryRunsEnabledDestinationsAndReusesConfigurationCache() throws IOException {
        writeBuildFile(enabledDestinations(), "");
        Files.createDirectories(projectDirectory.resolve("build/libs"));
        Files.writeString(projectDirectory.resolve("build/libs/decoy.jar"), "must not publish\n");

        BuildResult first = runGradle("publishMods", "--configuration-cache");
        BuildResult second = runGradle("publishMods", "--configuration-cache");

        assertEquals(TaskOutcome.SUCCESS, first.task(":publishGithub").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":publishModrinth").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":publishCurseforge").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        assertFalse(first.getOutput().contains("decoy.jar"));
        for (String taskName : List.of("publishGithub", "publishModrinth", "publishCurseforge")) {
            Path dryRunDirectory = projectDirectory.resolve("build/publishMods").resolve(taskName);
            assertTrue(Files.isRegularFile(dryRunDirectory.resolve("publisher-fixture-1.2.3.jar")));
            assertFalse(Files.exists(dryRunDirectory.resolve("decoy.jar")));
        }
    }

    @Test
    void acceptsASharedReleaseTagForALoaderSpecificVersionNumber() throws IOException {
        writeBuildFile("""
                dryRun = true
                version = '1.2.3-neoforge'
                releaseTag = 'v1.2.3'
                github {
                    repository = 'brainage04/publisher-fixture'
                    commitish = 'main'
                }
                """, "");

        BuildResult result = runGradle("publishGithub");

        assertEquals(TaskOutcome.SUCCESS, result.task(":validateModPublication").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":publishGithub").getOutcome());
    }

    @Test
    void preparesProjectMetadataWithRepositoryAndFabricPrecedence() throws IOException {
        writeBuildFile("""
                github {
                    repository = 'brainage04/publisher-fixture'
                    commitish = 'main'
                }
                modrinth {
                    repositoryDescription = 'Repository description wins'
                }
                """, "");

        BuildResult result = runGradle("prepareModrinthProjectMetadata");
        JsonObject metadata = JsonParser.parseString(Files.readString(
                projectDirectory.resolve("build/mod-publishing/modrinth-project-metadata.json"),
                StandardCharsets.UTF_8
        )).getAsJsonObject();
        JsonObject create = metadata.getAsJsonObject("create");
        JsonObject update = metadata.getAsJsonObject("update");

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareModrinthProjectMetadata").getOutcome());
        assertEquals("publisher-fixture", metadata.get("slug").getAsString());
        assertEquals("Publisher Fixture", create.get("title").getAsString());
        assertEquals("Repository description wins", create.get("description").getAsString());
        assertEquals("required", create.get("client_side").getAsString());
        assertEquals("required", create.get("server_side").getAsString());
        assertEquals("https://example.invalid/source", update.get("source_url").getAsString());
        assertEquals("https://github.com/brainage04/publisher-fixture/issues", update.get("issues_url").getAsString());
        assertTrue(metadata.get("has_icon").getAsBoolean());
    }

    @Test
    void infersDependenciesAndHonorsProjectOverrides() throws IOException {
        writeBuildFile(enabledDestinations(), """
                tasks.register('verifyInferredMetadata') {
                    doLast {
                        def destination = publishMods.platforms.getByName('modrinth')
                        assert destination.minecraftVersions.get() == ['26.1.2']
                        assert destination.modLoaders.get() == ['fabric']
                        assert !destination.featured.get()
                        def dependencies = destination.dependencies.get()
                        assert dependencies.size() == 3
                        assert dependencies.any { it.slug.orNull == 'fzzy-config' && it.type.get().name() == 'REQUIRED' }
                        assert dependencies.any { it.id.orNull == 'AbCdEf12' && it.type.get().name() == 'INCOMPATIBLE' }
                        assert dependencies.any { it.id.orNull == 'ZyXwVu98' && it.type.get().name() == 'OPTIONAL' }
                        assert dependencies.every { it.slug.orNull != 'modmenu' }
                    }
                }
                """);

        BuildResult result = runGradle("verifyInferredMetadata");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyInferredMetadata").getOutcome());
    }

    @Test
    void createsAndUpdatesMissingModrinthProject() throws Exception {
        try (var server = FakeModrinthServer.start(false)) {
            writeBuildFile(modrinthSyncConfiguration(server.apiEndpoint()), "");

            Path githubEnvironment = projectDirectory.resolve("github-environment");
            BuildResult result = runGradleWithEnvironment(
                    Map.of("GITHUB_ENV", githubEnvironment.toString()),
                    "syncModrinthProject"
            );

            assertEquals(TaskOutcome.SUCCESS, result.task(":syncModrinthProject").getOutcome());
            RecordedRequest lookup = server.singleRequest("GET", "/project/publisher-fixture");
            assertEquals("fixture-token", lookup.authorization());
            RecordedRequest create = server.singleRequest("POST", "/project");
            assertTrue(create.utf8Body().contains("name=\"data\""));
            assertTrue(create.utf8Body().contains("\"slug\""));
            assertTrue(create.utf8Body().contains("publisher-fixture"));
            assertTrue(create.utf8Body().contains("name=\"icon\""));
            server.singleRequest("PATCH", "/project/publisher-fixture");
            JsonObject state = JsonParser.parseString(Files.readString(
                    projectDirectory.resolve("build/mod-publishing/modrinth-project-state.json")
            )).getAsJsonObject();
            assertEquals("AbCdEf12", state.get("id").getAsString());
            assertTrue(state.get("created").getAsBoolean());
            assertFalse(Files.exists(githubEnvironment));
        }
    }

    @Test
    void updatesChangedModrinthIconThenSkipsMatchingContent() throws Exception {
        try (var server = FakeModrinthServer.start(true)) {
            writeBuildFile(modrinthSyncConfiguration(server.apiEndpoint()), "");

            runGradle("syncModrinthIcon");
            BuildResult second = runGradle("syncModrinthIcon");

            assertEquals(TaskOutcome.SUCCESS, second.task(":syncModrinthIcon").getOutcome());
            assertEquals(1, server.requests("PATCH", "/project/AbCdEf12/icon").size());
            assertEquals(4, server.requests("GET", "/project/publisher-fixture").size());
            JsonObject state = JsonParser.parseString(Files.readString(
                    projectDirectory.resolve("build/mod-publishing/modrinth-icon-state.json")
            )).getAsJsonObject();
            assertEquals("unchanged", state.get("status").getAsString());
        }
    }

    @Test
    void resolvesAnnotatedTagNotesWithoutCallingGitHub() throws Exception {
        writeBuildFile("""
                changelog = ''
                github {
                    repository = 'brainage04/publisher-fixture'
                    token = 'fixture-token'
                }
                """, "");
        initializeGitRepository();
        runGit("tag", "-a", "v1.2.3", "-m", "Annotated release notes");

        runGradle("resolveModReleaseNotes");

        assertEquals(
                "Annotated release notes",
                Files.readString(projectDirectory.resolve("build/mod-publishing/release-notes.md")).strip()
        );
    }

    @Test
    void fallsBackToGitHubGeneratedReleaseNotes() throws Exception {
        try (var server = FakeModrinthServer.start(true)) {
            writeBuildFile("""
                    changelog = ''
                    github {
                        repository = 'brainage04/publisher-fixture'
                        token = 'fixture-token'
                        apiEndpoint = '%s'
                    }
                    """.formatted(server.apiEndpoint()), "");
            initializeGitRepository();

            runGradle("resolveModReleaseNotes");

            assertEquals(
                    "Generated release notes",
                    Files.readString(projectDirectory.resolve("build/mod-publishing/release-notes.md")).strip()
            );
            RecordedRequest request = server.singleRequest(
                    "POST",
                    "/repos/brainage04/publisher-fixture/releases/generate-notes"
            );
            assertTrue(request.utf8Body().contains("\"tag_name\":\"v1.2.3\""));
        }
    }

    @Test
    void skipsExistingModrinthVersionThenPublishesWhenRetryIsNeeded() throws Exception {
        try (var server = FakeModrinthServer.start(true, true)) {
            writeBuildFile("""
                    dryRun = false
                    modrinth {
                        enabled = true
                        projectId = 'AbCdEf12'
                        token = 'fixture-token'
                        apiEndpoint = '%s'
                    }
                    """.formatted(server.apiEndpoint()), "");

            BuildResult skipped = runGradle("publishModrinth");

            assertEquals(TaskOutcome.SKIPPED, skipped.task(":publishModrinth").getOutcome());
            assertEquals(0, server.requests("POST", "/version").size());

            server.setPublishedVersionExists(false);
            BuildResult published = runGradle("publishModrinth");

            assertEquals(TaskOutcome.SUCCESS, published.task(":publishModrinth").getOutcome());
            assertEquals(1, server.requests("POST", "/version").size());
        }
    }

    @Test
    void skipsExistingGitHubAssetThenResumesIncompleteRelease() throws Exception {
        try (var server = FakeModrinthServer.start(true)) {
            server.setGitHubReleaseState(true, true);
            writeBuildFile("""
                    dryRun = false
                    github {
                        enabled = true
                        repository = 'brainage04/publisher-fixture'
                        commitish = 'main'
                        token = 'fixture-token'
                        apiEndpoint = '%s'
                    }
                    """.formatted(server.apiEndpoint()), "");

            BuildResult skipped = runGradle("publishGithub");

            assertEquals(TaskOutcome.SKIPPED, skipped.task(":publishGithub").getOutcome());
            assertEquals(0, server.requests("POST", "/uploads/42").size());

            server.setGitHubReleaseState(true, false);
            BuildResult resumed = runGradle("publishGithub");

            assertEquals(TaskOutcome.SUCCESS, resumed.task(":publishGithub").getOutcome());
            assertEquals(0, server.requests("POST", "/repos/brainage04/publisher-fixture/releases").size());
            RecordedRequest upload = server.singleRequest("POST", "/uploads/42");
            assertEquals("name=publisher-fixture-1.2.3.jar", upload.query());
        }
    }

    @Test
    void mapsStableBetaAndAlphaReleaseTagsForModrinth() throws Exception {
        try (var server = FakeModrinthServer.start(true, false)) {
            for (String tag : List.of("v1.2.3", "v1.2.3-beta.1", "v1.2.3-alpha.1")) {
                server.setPublishedVersionExists(false);
                writeBuildFile("""
                        dryRun = false
                        releaseTag = '%s'
                        version = '%s'
                        modrinth {
                            enabled = true
                            projectId = 'AbCdEf12'
                            token = 'fixture-token'
                            apiEndpoint = '%s'
                        }
                        """.formatted(tag, tag.substring(1), server.apiEndpoint()), "");
                runGradle("publishModrinth");
            }

            List<RecordedRequest> uploads = server.requests("POST", "/version");
            assertEquals(3, uploads.size());
            assertTrue(uploads.get(0).latin1Body().contains("\"version_type\":\"release\""));
            assertTrue(uploads.get(1).latin1Body().contains("\"version_type\":\"beta\""));
            assertTrue(uploads.get(2).latin1Body().contains("\"version_type\":\"alpha\""));
            assertTrue(uploads.stream().allMatch(
                    upload -> upload.latin1Body().contains("filename=\"publisher-fixture-1.2.3.jar\"")
            ));
        }
    }

    @Test
    void rejectsLivePublicationWithoutDestinationToken() throws IOException {
        writeBuildFile("""
                dryRun = false
                modrinth {
                    enabled = true
                    projectId = 'AbCdEf12'
                }
                """, "");

        BuildResult result = runGradleAndFail("publishModrinth");

        assertTrue(result.getOutput().contains("modPublishing.modrinth.token or MODRINTH_TOKEN"));
    }

    @Test
    void retriesDestinationsIndependentlyAfterPartialFailure() throws Exception {
        try (var server = FakeModrinthServer.start(true, false)) {
            server.setGitHubFailuresRemaining(3);
            writeBuildFile("""
                    dryRun = false
                    github {
                        enabled = true
                        repository = 'brainage04/publisher-fixture'
                        commitish = 'main'
                        token = 'fixture-token'
                        apiEndpoint = '%s'
                    }
                    modrinth {
                        enabled = true
                        projectId = 'AbCdEf12'
                        token = 'fixture-token'
                        apiEndpoint = '%s'
                    }
                    """.formatted(server.apiEndpoint(), server.apiEndpoint()), "");

            BuildResult githubFailure = runGradleAndFail("publishGithub");
            assertEquals(TaskOutcome.FAILED, githubFailure.task(":publishGithub").getOutcome());
            assertEquals(0, server.requests("POST", "/version").size());
            server.setGitHubFailuresRemaining(0);

            BuildResult modrinthSuccess = runGradle("publishModrinth");
            assertEquals(TaskOutcome.SUCCESS, modrinthSuccess.task(":publishModrinth").getOutcome());

            BuildResult githubRetry = runGradle("publishGithub");
            assertEquals(TaskOutcome.SUCCESS, githubRetry.task(":publishGithub").getOutcome());
            assertEquals(1, server.requests("POST", "/uploads/42").size());
            RecordedRequest create = server.requests(
                    "POST",
                    "/repos/brainage04/publisher-fixture/releases"
            ).getLast();
            assertTrue(create.utf8Body().contains("\"draft\":true"));
            assertTrue(create.utf8Body().contains("\"prerelease\":false"));
        }
    }

    @Test
    void ordinaryBuildAndCheckDoNotContactPublicationEndpoints() throws Exception {
        try (var server = FakeModrinthServer.start(true)) {
            writeBuildFile(modrinthSyncConfiguration(server.apiEndpoint()), "");

            BuildResult result = runGradle("build", "check");

            assertEquals(TaskOutcome.SUCCESS, result.task(":build").getOutcome());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void resolvesWorkflowPathsFromMultiprojectRoot() throws IOException {
        Files.writeString(projectDirectory.resolve("settings.gradle"), """
                rootProject.name = 'publisher-fixture'
                include 'fabric'
                """);
        Path releaseJar = projectDirectory.resolve("release-artifacts/fabric.jar");
        Files.createDirectories(releaseJar.getParent());
        Files.writeString(releaseJar, "artifact");
        Path fabricDirectory = projectDirectory.resolve("fabric");
        Files.createDirectories(fabricDirectory);
        Files.writeString(fabricDirectory.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id '%s'
                }

                tasks.register('printReleaseJar') {
                    doLast {
                        println('RELEASE_JAR=' + modPublishing.releaseJar.get().asFile.canonicalPath)
                        println('PROJECT_CONFIG=' + modPublishing.modrinth.projectConfig.get().asFile.canonicalPath)
                        println('PROJECT_BODY=' + modPublishing.modrinth.projectBody.get().asFile.canonicalPath)
                        println('LICENSE_FILE=' + modPublishing.licenseFile.get().asFile.canonicalPath)
                    }
                }
                """.formatted(PLUGIN_ID));

        BuildResult result = runGradleWithEnvironment(
                Map.of("MOD_PUBLISHING_RELEASE_JAR", "release-artifacts/fabric.jar"),
                ":fabric:printReleaseJar"
        );

        assertTrue(result.getOutput().contains("RELEASE_JAR=" + releaseJar.toFile().getCanonicalPath()));
        assertTrue(result.getOutput().contains(
                "PROJECT_CONFIG=" + projectDirectory.resolve(".modrinth/project.json").toFile().getCanonicalPath()
        ));
        assertTrue(result.getOutput().contains(
                "PROJECT_BODY=" + projectDirectory.resolve("README.md").toFile().getCanonicalPath()
        ));
        assertTrue(result.getOutput().contains(
                "LICENSE_FILE=" + projectDirectory.resolve("LICENSE").toFile().getCanonicalPath()
        ));
    }

    private String modrinthSyncConfiguration(String apiEndpoint) {
        return """
                github.repository = 'brainage04/publisher-fixture'
                modrinth {
                    token = 'fixture-token'
                    apiEndpoint = '%s'
                }
                """.formatted(apiEndpoint);
    }

    private String enabledDestinations() {
        return """
                dryRun = true
                github {
                    repository = 'brainage04/publisher-fixture'
                    commitish = 'main'
                }
                modrinth {
                    projectId = 'AbCdEf12'
                }
                curseforge {
                    projectId = '12345'
                    projectSlug = 'publisher-fixture'
                }
                """;
    }

    private void writeBuildFile(String publishingConfiguration, String additionalConfiguration) throws IOException {
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id '%s'
                }

                version = '1.2.3'

                tasks.register('releaseJar', Jar) {
                    archiveFileName = 'publisher-fixture-1.2.3.jar'
                    destinationDirectory = layout.buildDirectory.dir('release')
                    from('payload.txt')
                }

                modPublishing {
                    version = '1.2.3'
                    releaseTag = 'v1.2.3'
                    displayName = 'Publisher Fixture 1.2.3'
                    changelog = 'Release notes'
                    releaseJar = tasks.named('releaseJar', Jar).flatMap { it.archiveFile }
                %s
                }

                %s
                """.formatted(PLUGIN_ID, publishingConfiguration.indent(4), additionalConfiguration));
    }

    private BuildResult runGradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private BuildResult runGradleWithEnvironment(Map<String, String> additionalEnvironment, String... arguments) {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        environment.putAll(additionalEnvironment);
        return GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withEnvironment(environment)
                .withArguments(arguments)
                .build();
    }

    private void initializeGitRepository() throws Exception {
        runGit("init");
        runGit("config", "user.name", "Publisher Fixture");
        runGit("config", "user.email", "fixture@example.invalid");
        runGit("add", ".");
        runGit("commit", "-m", "Initial fixture");
    }

    private void runGit(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private BuildResult runGradleAndFail(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .buildAndFail();
    }
    private static final class FakeModrinthServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = new ArrayList<>();
        private volatile boolean projectExists;
        private volatile boolean publishedVersionExists;
        private volatile boolean githubReleaseExists;
        private volatile boolean githubAssetExists;
        private volatile String iconUrl = "https://cdn.modrinth.com/data/AbCdEf12/old_icon.png";
        private volatile int githubFailuresRemaining;

        private FakeModrinthServer(
                HttpServer server,
                ExecutorService executor,
                boolean projectExists,
                boolean publishedVersionExists
        ) {
            this.server = server;
            this.executor = executor;
            this.projectExists = projectExists;
            this.publishedVersionExists = publishedVersionExists;
        }

        static FakeModrinthServer start(boolean projectExists) throws IOException {
            return start(projectExists, false);
        }

        static FakeModrinthServer start(boolean projectExists, boolean publishedVersionExists) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            FakeModrinthServer fixture = new FakeModrinthServer(
                    server,
                    executor,
                    projectExists,
                    publishedVersionExists
            );
            server.createContext("/", fixture::handle);
            server.setExecutor(executor);
            server.start();
            return fixture;
        }

        String apiEndpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        synchronized List<RecordedRequest> requests(String method, String path) {
            return requests.stream()
                    .filter(request -> request.method().equals(method) && request.path().equals(path))
                    .toList();
        }

        synchronized int requestCount() {
            return requests.size();
        }

        void setPublishedVersionExists(boolean publishedVersionExists) {
            this.publishedVersionExists = publishedVersionExists;
        }

        void setGitHubReleaseState(boolean releaseExists, boolean assetExists) {
            githubReleaseExists = releaseExists;
            githubAssetExists = assetExists;
        }

        void setGitHubFailuresRemaining(int failures) {
            githubFailuresRemaining = failures;
        }

        RecordedRequest singleRequest(String method, String path) {
            List<RecordedRequest> matches = requests(method, path);
            assertEquals(1, matches.size(), () -> method + " " + path + " requests: " + requests);
            return matches.getFirst();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            synchronized (this) {
                requests.add(new RecordedRequest(
                        exchange.getRequestMethod(),
                        path,
                        exchange.getRequestURI().getRawQuery(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        requestBody
                ));
            }

            if ("GET".equals(exchange.getRequestMethod())
                    && "/repos/brainage04/publisher-fixture/releases/tags/v1.2.3".equals(path)) {
                if (!githubReleaseExists) {
                    respond(exchange, 404, "{}");
                    return;
                }
                respond(exchange, 200, githubReleaseResponse());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && "/repos/brainage04/publisher-fixture/releases/42".equals(path)) {
                respond(exchange, 200, githubReleaseResponse());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && "/repos/brainage04/publisher-fixture".equals(path)) {
                respond(exchange, 200, "{\"full_name\":\"brainage04/publisher-fixture\"}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())
                    && "/repos/brainage04/publisher-fixture/releases".equals(path)) {
                if (githubFailuresRemaining > 0) {
                    githubFailuresRemaining--;
                    respond(exchange, 500, "{\"message\":\"fixture GitHub failure\"}");
                    return;
                }
                githubReleaseExists = true;
                respond(exchange, 200, githubReleaseResponse());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/uploads/42".equals(path)) {
                githubAssetExists = true;
                respond(exchange, 200, "{}");
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod())
                    && "/repos/brainage04/publisher-fixture/releases/42".equals(path)) {
                respond(exchange, 200, githubReleaseResponse());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/project/publisher-fixture".equals(path)) {
                if (projectExists) {
                    respond(exchange, 200, """
                            {"id":"AbCdEf12","icon_url":"%s"}
                            """.formatted(iconUrl));
                } else {
                    respond(exchange, 404, "{}");
                }
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/project/AbCdEf12/version".equals(path)) {
                respond(
                        exchange,
                        200,
                        publishedVersionExists
                                ? "[{\"id\":\"version-1\",\"version_number\":\"1.2.3\"}]"
                                : "[]"
                );
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/version".equals(path)) {
                publishedVersionExists = true;
                respond(
                        exchange,
                        200,
                        "{\"id\":\"version-1\",\"project_id\":\"AbCdEf12\",\"author_id\":\"author-1\"}"
                );
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/project/fzzy-config/check".equals(path)) {
                respond(exchange, 200, "{\"id\":\"Fzzy1234\"}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/project".equals(path)) {
                projectExists = true;
                respond(exchange, 200, "{\"id\":\"AbCdEf12\"}");
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod()) && "/project/publisher-fixture".equals(path)) {
                respond(exchange, 204, "");
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod()) && "/project/AbCdEf12/icon".equals(path)) {
                iconUrl = "https://cdn.modrinth.com/data/AbCdEf12/" + sha1(requestBody) + "_icon.png";
                respond(exchange, 204, "");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())
                    && "/repos/brainage04/publisher-fixture/releases/generate-notes".equals(path)) {
                respond(exchange, 200, "{\"body\":\"Generated release notes\"}");
                return;
            }
            respond(exchange, 404, "{}");
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private String githubReleaseResponse() {
            String assets = githubAssetExists
                    ? "[{\"name\":\"publisher-fixture-1.2.3.jar\"}]"
                    : "[]";
            return """
                    {
                      "id": 42,
                      "name": "Publisher fixture 1.2.3",
                      "html_url": "%s/releases/42",
                      "upload_url": "%s/uploads/42{?name,label}",
                      "assets": %s
                    }
                    """.formatted(apiEndpoint(), apiEndpoint(), assets);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private record RecordedRequest(
            String method,
            String path,
            String query,
            String authorization,
            byte[] body
    ) {
        String utf8Body() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String latin1Body() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
