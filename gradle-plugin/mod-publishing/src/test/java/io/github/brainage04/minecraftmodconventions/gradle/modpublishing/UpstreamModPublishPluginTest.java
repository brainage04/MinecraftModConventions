package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamModPublishPluginTest {
    @TempDir
    Path temporaryDirectory;

    Path projectDirectory;

    @BeforeEach
    void createProjectDirectory() throws IOException {
        projectDirectory = Files.createDirectory(temporaryDirectory.resolve("consumer"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'publisher-fixture'\n");
        Files.writeString(projectDirectory.resolve("payload.txt"), "exact release payload\n");
        Files.writeString(projectDirectory.resolve("decoy.txt"), "must not be published\n");
        writeBuildFile("http://127.0.0.1:1", true);
    }

    @Test
    void dryRunPublishesOnlyTheConfiguredReleaseJarToEveryPlatform() {
        BuildResult result = runGradle("publishMods");

        assertEquals(TaskOutcome.SUCCESS, result.task(":releaseJar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":publishGithub").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":publishModrinth").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":publishCurseforge").getOutcome());

        assertDryRunContainsOnlyReleaseJar("publishGithub");
        assertDryRunContainsOnlyReleaseJar("publishModrinth");
        assertDryRunContainsOnlyReleaseJar("publishCurseforge");
    }

    @Test
    void dryRunReusesTheGradleConfigurationCache() {
        BuildResult first = runGradle("publishGithub", "--configuration-cache");
        BuildResult second = runGradle("publishGithub", "--configuration-cache");

        assertEquals(TaskOutcome.SUCCESS, first.task(":publishGithub").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, second.task(":publishGithub").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
    }

    @Test
    void publishesAgainstFakeGitHubModrinthAndCurseForgeEndpoints() throws Exception {
        try (var server = FakePublishingServer.start()) {
            writeBuildFile(server.baseUrl(), false);

            BuildResult result = runGradle("publishMods");

            assertEquals(TaskOutcome.SUCCESS, result.task(":publishGithub").getOutcome());
            assertEquals(TaskOutcome.SUCCESS, result.task(":publishModrinth").getOutcome());
            assertEquals(TaskOutcome.SUCCESS, result.task(":publishCurseforge").getOutcome());

            RecordedRequest githubCreate = server.request("POST", "/github/repos/brainage04/publisher-fixture/releases");
            assertTrue(githubCreate.utf8Body().contains("\"draft\":true"));
            assertTrue(githubCreate.utf8Body().contains("\"prerelease\":false"));
            assertEquals("token fixture-token", githubCreate.header("Authorization"));

            RecordedRequest githubUpload = server.request("POST", "/github/uploads/42");
            assertEquals("name=publisher-fixture-1.2.3.jar", githubUpload.query());
            assertTrue(githubUpload.body().length > 0);
            assertEquals("application/java-archive", githubUpload.header("Content-Type"));

            RecordedRequest githubPublish = server.request("PATCH", "/github/repos/brainage04/publisher-fixture/releases/42");
            assertEquals("{\"draft\":false}", githubPublish.utf8Body());

            RecordedRequest modrinthUpload = server.request("POST", "/modrinth/version");
            String modrinthBody = modrinthUpload.latin1Body();
            assertTrue(modrinthBody.contains("filename=\"publisher-fixture-1.2.3.jar\""));
            assertTrue(modrinthBody.contains("\"version_number\":\"1.2.3\""));
            assertTrue(modrinthBody.contains("\"game_versions\":[\"26.1.2\"]"));
            assertTrue(modrinthBody.contains("\"loaders\":[\"fabric\"]"));
            assertEquals("fixture-token", modrinthUpload.header("Authorization"));

            server.request("GET", "/curseforge/api/game/version-types");
            server.request("GET", "/curseforge/api/game/versions");
            RecordedRequest curseforgeUpload = server.request("POST", "/curseforge/api/projects/12345/upload-file");
            String curseforgeBody = curseforgeUpload.latin1Body();
            assertTrue(curseforgeBody.contains("filename=\"publisher-fixture-1.2.3.jar\""));
            assertTrue(curseforgeBody.contains("\"releaseType\":\"release\""));
            assertTrue(curseforgeBody.contains("\"gameVersions\":[101,102,103]"));
            assertEquals("fixture-token", curseforgeUpload.header("X-Api-Token"));
        }
    }

    private void assertDryRunContainsOnlyReleaseJar(String taskName) {
        Path outputDirectory = projectDirectory.resolve("build/publishMods").resolve(taskName);
        assertTrue(Files.isRegularFile(outputDirectory.resolve("publisher-fixture-1.2.3.jar")));
        assertFalse(Files.exists(outputDirectory.resolve("publisher-fixture-decoy-1.2.3.jar")));
    }

    private void writeBuildFile(String apiBaseUrl, boolean dryRun) throws IOException {
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'base'
                    id 'me.modmuss50.mod-publish-plugin'
                }

                version = '1.2.3'

                def releaseJar = tasks.register('releaseJar', Jar) {
                    archiveBaseName = 'publisher-fixture'
                    archiveVersion = project.version
                    destinationDirectory = layout.buildDirectory.dir('release')
                    from('payload.txt')
                }

                tasks.register('decoyJar', Jar) {
                    archiveBaseName = 'publisher-fixture-decoy'
                    archiveVersion = project.version
                    destinationDirectory = layout.buildDirectory.dir('release')
                    from('decoy.txt')
                }

                publishMods {
                    file.set(releaseJar.flatMap { it.archiveFile })
                    version.set(project.version.toString())
                    displayName.set("Publisher fixture ${project.version}")
                    changelog.set('Fixture changelog')
                    type.set(STABLE)
                    modLoaders.add('fabric')
                    dryRun.set(%s)

                    github {
                        repository.set('brainage04/publisher-fixture')
                        commitish.set('main')
                        tagName.set(project.version.toString())
                        accessToken.set('fixture-token')
                        apiEndpoint.set('%s/github')
                    }

                    modrinth {
                        projectId.set('AbCdEf12')
                        minecraftVersions.add('26.1.2')
                        accessToken.set('fixture-token')
                        apiEndpoint.set('%s/modrinth')
                    }

                    curseforge {
                        projectId.set('12345')
                        minecraftVersions.add('26.1.2')
                        client.set(true)
                        accessToken.set('fixture-token')
                        apiEndpoint.set('%s/curseforge')
                    }
                }

                tasks.withType(me.modmuss50.mpp.PublishModTask).configureEach {
                    dependsOn(releaseJar)
                }
                """.formatted(dryRun, apiBaseUrl, apiBaseUrl, apiBaseUrl));
    }

    private record RecordedRequest(
            String method,
            String path,
            String query,
            Map<String, List<String>> headers,
            byte[] body
    ) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .flatMap(List::stream)
                    .findFirst()
                    .orElse(null);
        }

        String utf8Body() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String latin1Body() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    private static final class FakePublishingServer implements AutoCloseable {
        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        private FakePublishingServer(HttpServer server) {
            this.server = server;
        }

        static FakePublishingServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakePublishingServer fixture = new FakePublishingServer(server);
            server.createContext("/", fixture::handle);
            server.start();
            return fixture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        RecordedRequest request(String method, String path) {
            return requests.stream()
                    .filter(request -> request.method().equals(method) && request.path().equals(path))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing request " + method + " " + path + ": " + requests));
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    requestBody
            ));

            String response = responseFor(exchange);
            byte[] responseBody = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        }

        private String responseFor(HttpExchange exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (method.equals("GET") && path.equals("/github/repos/brainage04/publisher-fixture")) {
                return "{\"full_name\":\"brainage04/publisher-fixture\"}";
            }
            if (method.equals("POST") && path.equals("/github/repos/brainage04/publisher-fixture/releases")
                    || method.equals("PATCH") && path.equals("/github/repos/brainage04/publisher-fixture/releases/42")) {
                return "{\"id\":42,\"html_url\":\"" + baseUrl()
                        + "/github/releases/42\",\"upload_url\":\"" + baseUrl()
                        + "/github/uploads/42{?name,label}\"}";
            }
            if (method.equals("POST") && path.equals("/github/uploads/42")) {
                return "{}";
            }
            if (method.equals("POST") && path.equals("/modrinth/version")) {
                return "{\"id\":\"version-1\",\"project_id\":\"project-1\",\"author_id\":\"author-1\"}";
            }
            if (method.equals("GET") && path.equals("/curseforge/api/game/version-types")) {
                return "["
                        + "{\"id\":1,\"name\":\"Minecraft\",\"slug\":\"minecraft-release\"},"
                        + "{\"id\":2,\"name\":\"Modloader\",\"slug\":\"modloader\"},"
                        + "{\"id\":3,\"name\":\"Environment\",\"slug\":\"environment\"}"
                        + "]";
            }
            if (method.equals("GET") && path.equals("/curseforge/api/game/versions")) {
                return "["
                        + "{\"id\":101,\"gameVersionTypeID\":1,\"name\":\"26.1.2\",\"slug\":\"26-1-2\"},"
                        + "{\"id\":102,\"gameVersionTypeID\":2,\"name\":\"fabric\",\"slug\":\"fabric\"},"
                        + "{\"id\":103,\"gameVersionTypeID\":3,\"name\":\"client\",\"slug\":\"client\"}"
                        + "]";
            }
            if (method.equals("POST") && path.equals("/curseforge/api/projects/12345/upload-file")) {
                return "{\"id\":555}";
            }

            throw new AssertionError("Unexpected request " + method + " " + path);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private BuildResult runGradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }
}
