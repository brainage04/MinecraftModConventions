package io.github.brainage04.minecraftmodconventions.gradle.production;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.GradleException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A plain vanilla dedicated server for client GameTests: Mojang's server jar for a Minecraft version, started offline,
 * on a flat creative world and a free local port, with its console in {@code console.log} of its directory.
 */
public final class VanillaServer implements AutoCloseable {
    public static final String DEFAULT_VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Pattern READY = Pattern.compile("]: Done \\(");
    private static final Pattern JOINED = Pattern.compile("]: (?:System chat: )?(\\S+) joined the game");
    private static final Pattern CONNECTION_EVENT =
            Pattern.compile("joined the game|left the game|lost connection|Failed to decode packet");
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    private final Process process;
    private final Path directory;
    private final int port;

    private VanillaServer(Process process, Path directory, int port) {
        this.process = process;
        this.directory = directory;
        this.port = port;
    }

    /**
     * The server jar of {@code version}, downloaded once into {@code cacheDirectory/<version>/server.jar} and checked
     * against the SHA-1 Mojang publishes. A cached jar whose recorded SHA-1 still matches is used without going online.
     */
    public static Path serverJar(Path cacheDirectory, String version, URI versionManifest) {
        Path versionDirectory = cacheDirectory.resolve(version);
        Path jar = versionDirectory.resolve("server.jar");
        Path recordedSha1 = versionDirectory.resolve("server.jar.sha1");
        try {
            if (Files.isRegularFile(jar) && Files.isRegularFile(recordedSha1)
                    && Files.readString(recordedSha1).strip().equals(sha1(jar))) {
                return jar;
            }
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            JsonObject manifest = parseObject(fetchText(http, versionManifest), versionManifest);
            URI versionUri = null;
            for (JsonElement entry : manifest.getAsJsonArray("versions")) {
                JsonObject candidate = entry.getAsJsonObject();
                if (version.equals(candidate.get("id").getAsString())) {
                    versionUri = versionManifest.resolve(candidate.get("url").getAsString());
                }
            }
            if (versionUri == null) {
                throw new GradleException("Minecraft " + version + " is not in the version manifest " + versionManifest);
            }
            JsonObject versionJson = parseObject(fetchText(http, versionUri), versionUri);
            JsonObject downloads = versionJson.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("server")) {
                throw new GradleException("Minecraft " + version + " has no dedicated server download (" + versionUri + ")");
            }
            JsonObject server = downloads.getAsJsonObject("server");
            URI serverUri = versionUri.resolve(server.get("url").getAsString());
            String expectedSha1 = server.get("sha1").getAsString();

            Files.createDirectories(versionDirectory);
            Path partial = Files.createTempFile(versionDirectory, "server", ".jar.part");
            try {
                HttpResponse<Path> response = http.send(
                        HttpRequest.newBuilder(serverUri).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(partial));
                requireOk(response.statusCode(), serverUri);
                String actualSha1 = sha1(partial);
                if (!actualSha1.equals(expectedSha1)) {
                    throw new GradleException("The server jar from " + serverUri + " has SHA-1 " + actualSha1
                            + ", but Mojang publishes " + expectedSha1);
                }
                Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.writeString(recordedSha1, expectedSha1 + "\n");
            } finally {
                Files.deleteIfExists(partial);
            }
            return jar;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to get the Minecraft " + version + " server jar", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while downloading the Minecraft " + version + " server jar", exception);
        }
    }

    /** What the server runs with: offline (any client name joins), no whitelist, a flat creative world on {@code port}. */
    public static Properties serverProperties(int port) {
        Properties properties = new Properties();
        properties.setProperty("online-mode", "false");
        properties.setProperty("enforce-secure-profile", "false");
        properties.setProperty("white-list", "false");
        properties.setProperty("enforce-whitelist", "false");
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("server-port", Integer.toString(port));
        properties.setProperty("gamemode", "creative");
        properties.setProperty("force-gamemode", "true");
        properties.setProperty("level-type", "minecraft:flat");
        properties.setProperty("generate-structures", "false");
        properties.setProperty("spawn-protection", "0");
        properties.setProperty("view-distance", "4");
        properties.setProperty("simulation-distance", "4");
        return properties;
    }

    /**
     * Starts {@code jar} with {@code java} in a fresh {@code directory} (its previous contents are deleted) and waits
     * until the server is ready for clients.
     */
    public static VanillaServer start(Path java, Path jar, Path directory, String maxHeap, Duration startupTimeout) {
        try {
            deleteRecursively(directory);
            Files.createDirectories(directory);
            int port = freePort();
            Files.writeString(directory.resolve("eula.txt"), "eula=true\n");
            try (Writer writer = Files.newBufferedWriter(directory.resolve("server.properties"))) {
                serverProperties(port).store(writer, "Written by MinecraftModConventions' runVanillaServerClientGameTest");
            }
            Path console = directory.resolve("console.log");
            Process process = new ProcessBuilder(
                    java.toString(), "-Xmx" + maxHeap, "-jar", jar.toAbsolutePath().toString(), "nogui")
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(console.toFile())
                    .start();
            VanillaServer server = new VanillaServer(process, directory, port);
            try {
                server.awaitReady(startupTimeout);
            } catch (RuntimeException exception) {
                server.close();
                throw exception;
            }
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to start the vanilla server in " + directory, exception);
        }
    }

    private void awaitReady(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (READY.matcher(consoleText()).find()) {
                return;
            }
            if (!process.isAlive()) {
                throw new GradleException("The vanilla server exited with code " + process.exitValue()
                        + " before it was ready; see " + console());
            }
            sleep(Duration.ofMillis(250));
        }
        throw new GradleException("The vanilla server was not ready within " + timeout.toSeconds() + " s; see " + console());
    }

    /** {@code 127.0.0.1:<port>}, what a client connects to. */
    public String address() {
        return "127.0.0.1:" + port;
    }

    public Path directory() {
        return directory;
    }

    public Path console() {
        return directory.resolve("console.log");
    }

    /** The names of the players that joined, in order. */
    public List<String> joinedPlayers() {
        return joinedPlayers(consoleText());
    }

    static List<String> joinedPlayers(String console) {
        return JOINED.matcher(console).results().map(result -> result.group(1)).toList();
    }

    /** The console lines about players joining, leaving or being dropped. */
    public List<String> connectionEvents() {
        return connectionEvents(consoleText());
    }

    static List<String> connectionEvents(String console) {
        return console.lines().filter(line -> CONNECTION_EVENT.matcher(line).find()).toList();
    }

    private String consoleText() {
        try {
            return Files.isRegularFile(console()) ? Files.readString(console(), StandardCharsets.UTF_8) : "";
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + console(), exception);
        }
    }

    /** Asks the server to stop and save, and ends the process if it has not stopped within a minute. */
    @Override
    public void close() {
        if (process.isAlive()) {
            try (OutputStream input = process.getOutputStream()) {
                input.write("stop\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The process may have closed its input while stopping on its own.
            }
        }
        try {
            if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String fetchText(HttpClient http, URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        requireOk(response.statusCode(), uri);
        return response.body();
    }

    private static void requireOk(int status, URI uri) {
        if (status != 200) {
            throw new GradleException("GET " + uri + " answered HTTP " + status);
        }
    }

    private static JsonObject parseObject(String json, URI source) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new GradleException("Expected a JSON object from " + source);
        }
        return parsed.getAsJsonObject();
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JVM has no SHA-1", exception);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while waiting for the vanilla server", exception);
        }
    }
}
