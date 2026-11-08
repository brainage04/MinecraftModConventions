package io.github.brainage04.minecraftmodconventions.gradle.production;

import com.sun.net.httpserver.HttpServer;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaServerTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    private static final Duration STARTUP = Duration.ofSeconds(30);

    /**
     * Stands in for Mojang's server jar: logs like the vanilla console, listens on {@code server-port} from
     * {@code server.properties}, logs a join for every connection and stops on {@code stop}.
     */
    private static final String FAKE_SERVER = """
            import java.io.*;
            import java.net.*;
            import java.nio.file.*;
            import java.util.Properties;

            public class FakeServer {
                public static void main(String[] args) throws Exception {
                    if (Files.exists(Path.of("exit-before-ready"))) {
                        System.exit(3);
                    }
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(Path.of("server.properties"))) {
                        properties.load(reader);
                    }
                    ServerSocket socket = new ServerSocket(Integer.parseInt(properties.getProperty("server-port")), 50,
                            InetAddress.getByName(properties.getProperty("server-ip")));
                    Thread accept = new Thread(() -> {
                        int player = 0;
                        while (true) {
                            try (Socket client = socket.accept()) {
                                player++;
                                System.out.println("[00:00:01] [Server thread/INFO]: System chat: Player" + player + " joined the game");
                                System.out.println("[00:00:02] [Server thread/INFO]: Player" + player + " lost connection: Disconnected");
                            } catch (IOException exception) {
                                return;
                            }
                        }
                    });
                    accept.setDaemon(true);
                    accept.start();
                    System.out.println("[00:00:00] [Server thread/INFO]: Starting minecraft server version 26.3");
                    System.out.println("[00:00:00] [Server thread/INFO]: Done (0.100s)! For help, type \\"help\\"");
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                    for (String line = input.readLine(); line != null; line = input.readLine()) {
                        if (line.equals("stop")) {
                            System.out.println("[00:00:03] [Server thread/INFO]: Stopping server");
                            System.exit(0);
                        }
                    }
                }
            }
            """;

    @TempDir
    Path temp;

    private HttpServer http;
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private Path serverJar;

    @BeforeEach
    void serveMojangMetadata() throws IOException {
        serverJar = buildFakeServerJar();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            synchronized (requests) {
                requests.add(exchange.getRequestURI().getPath());
            }
            byte[] body = responses.get(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
            exchange.close();
        });
        http.start();
        byte[] jar = Files.readAllBytes(serverJar);
        publish("/mc/game/version_manifest_v2.json", """
                {"latest": {"release": "26.3"}, "versions": [
                  {"id": "26.2", "url": "/v1/packages/old/26.2.json"},
                  {"id": "26.3", "url": "/v1/packages/abc/26.3.json"}
                ]}
                """);
        publish("/v1/packages/abc/26.3.json", """
                {"downloads": {"server": {"sha1": "%s", "size": %d, "url": "/v1/objects/def/server.jar"}}}
                """.formatted(sha1(jar), jar.length));
        responses.put("/v1/objects/def/server.jar", jar);
    }

    @AfterEach
    void stopHttp() {
        http.stop(0);
    }

    @Test
    void downloadsTheServerJarOnceCheckedAgainstMojangsSha1AndThenWorksOffline() throws IOException {
        Path cache = temp.resolve("cache");

        Path jar = VanillaServer.serverJar(cache, "26.3", manifest());

        assertEquals(cache.resolve("26.3/server.jar"), jar);
        assertEquals(sha1(Files.readAllBytes(serverJar)), sha1(Files.readAllBytes(jar)));
        assertEquals(List.of("/mc/game/version_manifest_v2.json", "/v1/packages/abc/26.3.json",
                "/v1/objects/def/server.jar"), requests);

        http.stop(0);
        assertEquals(jar, VanillaServer.serverJar(cache, "26.3", manifest()));
        try (var files = Files.list(cache.resolve("26.3"))) {
            assertEquals(List.of("server.jar", "server.jar.sha1"),
                    files.map(file -> file.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void aJarThatDoesNotMatchMojangsSha1IsRejectedAndNotCached() {
        responses.put("/v1/objects/def/server.jar", "not the server".getBytes(StandardCharsets.UTF_8));
        Path cache = temp.resolve("cache");

        GradleException failure = assertThrows(GradleException.class,
                () -> VanillaServer.serverJar(cache, "26.3", manifest()));

        assertTrue(failure.getMessage().contains("but Mojang publishes"), failure.getMessage());
        assertFalse(Files.exists(cache.resolve("26.3/server.jar")));
    }

    @Test
    void anUnknownVersionFailsWithTheManifestItWasLookedUpIn() {
        GradleException failure = assertThrows(GradleException.class,
                () -> VanillaServer.serverJar(temp.resolve("cache"), "26.9", manifest()));

        assertTrue(failure.getMessage().contains("Minecraft 26.9 is not in the version manifest"), failure.getMessage());
    }

    @Test
    void theServerIsOfflineFlatAndCreativeOnLoopback() {
        Properties properties = VanillaServer.serverProperties(25599);

        assertEquals("false", properties.getProperty("online-mode"));
        assertEquals("false", properties.getProperty("white-list"));
        assertEquals("false", properties.getProperty("enforce-secure-profile"));
        assertEquals("127.0.0.1", properties.getProperty("server-ip"));
        assertEquals("25599", properties.getProperty("server-port"));
        assertEquals("creative", properties.getProperty("gamemode"));
        assertEquals("true", properties.getProperty("force-gamemode"));
        assertEquals("minecraft:flat", properties.getProperty("level-type"));
    }

    @Test
    void startsInAFreshDirectoryOnAFreePortReportsJoinsAndStopsOnClose() throws IOException {
        Path directory = temp.resolve("vanillaServer");
        Files.createDirectories(directory.resolve("world"));
        Files.writeString(directory.resolve("world/level.dat"), "a previous run's world");

        List<String> joined;
        List<String> events;
        try (VanillaServer server = VanillaServer.start(JAVA, serverJar, directory, "64M", STARTUP)) {
            assertFalse(Files.exists(directory.resolve("world")));
            assertEquals("eula=true\n", Files.readString(directory.resolve("eula.txt")));
            Properties written = new Properties();
            try (InputStream input = Files.newInputStream(directory.resolve("server.properties"))) {
                written.load(input);
            }
            assertEquals("127.0.0.1:" + written.getProperty("server-port"), server.address());
            assertEquals("minecraft:flat", written.getProperty("level-type"));
            assertEquals(List.of(), server.joinedPlayers());

            String[] hostAndPort = server.address().split(":");
            try (Socket ignored = new Socket(hostAndPort[0], Integer.parseInt(hostAndPort[1]))) {
                waitFor(() -> server.connectionEvents().size() == 2);
            }
            joined = server.joinedPlayers();
            events = server.connectionEvents();
            assertTrue(serverProcessRunning(), "the server must run until it is closed");
        }

        assertEquals(List.of("Player1"), joined);
        assertEquals(2, events.size(), events.toString());
        assertTrue(Files.readString(directory.resolve("console.log")).contains("Stopping server"));
        assertFalse(serverProcessRunning(), "closing must stop the server");
    }

    private boolean serverProcessRunning() {
        return ProcessHandle.current().children()
                .anyMatch(child -> child.info().commandLine().orElse("").contains(serverJar.toString()));
    }

    @Test
    void aServerThatExitsBeforeItIsReadyFailsWithItsExitCode() throws IOException {
        Path directory = temp.resolve("vanillaServer");
        // The server's directory is emptied before it starts, so the marker that makes the fake server exit is
        // created by a launcher in front of java, in the server's working directory.
        Path java = writeJavaWrapper(temp.resolve("java-exits"), "touch exit-before-ready");

        GradleException failure = assertThrows(GradleException.class,
                () -> VanillaServer.start(java, serverJar, directory, "64M", STARTUP));

        assertTrue(failure.getMessage().contains("exited with code 3 before it was ready"), failure.getMessage());
    }

    @Test
    void joinAndConnectionLinesAreReadFromTheVanillaConsoleFormat() {
        String console = """
                [20:24:28] [Server thread/INFO]: System chat: Player891 joined the game
                [20:24:30] [Server thread/INFO]: Preparing spawn area
                [20:24:32] [Server thread/INFO]: Player891 lost connection: Internal Exception: io.netty.handler.codec.DecoderException: Failed to decode packet 'serverbound/minecraft:set_creative_mode_slot'
                [20:24:40] [Server thread/INFO]: Steve joined the game
                """;

        assertEquals(List.of("Player891", "Steve"), VanillaServer.joinedPlayers(console));
        assertEquals(3, VanillaServer.connectionEvents(console).size());
    }

    private URI manifest() {
        return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mc/game/version_manifest_v2.json");
    }

    private void publish(String path, String json) {
        responses.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private Path buildFakeServerJar() throws IOException {
        Path sources = Files.createDirectories(temp.resolve("fake-server-src"));
        Path source = sources.resolve("FakeServer.java");
        Files.writeString(source, FAKE_SERVER);
        Path classes = Files.createDirectories(temp.resolve("fake-server-classes"));
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, result, "the fake server must compile");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeServer");
        Path jar = temp.resolve("fake-server.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            try (var classFiles = Files.list(classes)) {
                for (Path classFile : classFiles.toList()) {
                    output.putNextEntry(new JarEntry(classFile.getFileName().toString()));
                    output.write(Files.readAllBytes(classFile));
                    output.closeEntry();
                }
            }
        }
        return jar;
    }

    private static Path writeJavaWrapper(Path script, String before) throws IOException {
        Files.writeString(script, "#!/bin/sh\n" + before + "\nexec '" + JAVA + "' \"$@\"\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
