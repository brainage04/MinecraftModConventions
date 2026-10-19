package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.JsonValues;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.http.PublishingHttpClient;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Uploads a Modrinth project icon only when its content hash has changed. */
@DisableCachingByDefault(because = "This task synchronizes remote Modrinth state")
public abstract class SyncModrinthIconTask extends DefaultTask {
    private static final long MAX_ICON_BYTES = 262_144L;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "bmp", "gif", "webp", "svg", "svgz", "rgb"
    );

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMetadataFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public abstract RegularFileProperty getIconFile();

    @Input
    public abstract Property<String> getApiEndpoint();

    @Input
    public abstract Property<String> getUserAgent();

    @Input
    public abstract Property<Integer> getMaxRetries();

    @Input
    public abstract Property<Boolean> getDryRun();

    @Internal
    public abstract Property<String> getToken();

    @OutputFile
    public abstract RegularFileProperty getStateFile();

    @TaskAction
    void syncIcon() throws IOException {
        JsonObject metadata = PrepareModrinthProjectMetadataTask.readObject(
                getMetadataFile().get().getAsFile().toPath()
        );
        String slug = JsonValues.string(metadata, "slug");
        if (slug.isBlank()) {
            throw new GradleException("Missing Modrinth project slug in " + getMetadataFile().get().getAsFile());
        }
        if (!getIconFile().isPresent()) {
            writeState("skipped", "No icon is configured");
            getLogger().lifecycle("No icon is configured; skipping Modrinth project icon sync.");
            return;
        }

        Path sourceIcon = getIconFile().get().getAsFile().toPath();
        PreparedIcon icon = prepareIcon(sourceIcon);
        String sha1 = sha1(icon.path());
        if (getDryRun().get()) {
            writeState("dry-run", sha1);
            getLogger().lifecycle("Would synchronize Modrinth project icon for {} from {}", slug, sourceIcon);
            return;
        }

        String token = getToken().getOrElse("").trim();
        if (token.isEmpty()) {
            throw new GradleException("Missing MODRINTH_TOKEN");
        }
        PublishingHttpClient client = new PublishingHttpClient(
                getApiEndpoint().get(),
                token,
                getUserAgent().get(),
                getMaxRetries().get()
        );
        PublishingHttpClient.Response projectResponse = client.get("/project/" + pathSegment(slug));
        if (projectResponse.status() != 200) {
            throw failure("fetch Modrinth project " + slug, projectResponse);
        }
        JsonObject project = parseObject(projectResponse.body());
        String projectId = JsonValues.string(project, "id");
        String currentIconUrl = JsonValues.string(project, "icon_url");
        if (currentIconUrl.contains("/" + sha1 + "_") || currentIconUrl.contains("/" + sha1 + ".")) {
            writeState("unchanged", sha1);
            getLogger().lifecycle("Modrinth project icon already matches {}; skipping icon sync.", sourceIcon);
            return;
        }

        PublishingHttpClient.Response updateResponse = client.patchBytes(
                "/project/" + pathSegment(projectId) + "/icon?ext=" + icon.extension(),
                icon.contentType(),
                Files.readAllBytes(icon.path())
        );
        if (updateResponse.status() != 204) {
            throw failure("synchronize Modrinth project icon for " + slug, updateResponse);
        }
        writeState("updated", sha1);
        getLogger().lifecycle("Synchronized Modrinth project icon for {} from {}", slug, sourceIcon);
    }

    static String contentType(Path icon) {
        String extension = extension(icon);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new GradleException("Unsupported Modrinth icon extension: " + extension);
        }
        return switch (extension) {
            case "jpg" -> "image/jpeg";
            case "svgz" -> "image/svgz";
            default -> "image/" + extension;
        };
    }

    private static PreparedIcon prepareIcon(Path source) throws IOException {
        String extension = extension(source);
        String contentType = contentType(source);
        Path upload = source;
        if ("png".equals(extension) && Files.size(source) > MAX_ICON_BYTES) {
            Path optimized = Files.createTempFile("modrinth-icon-", ".png");
            Process process;
            try {
                process = new ProcessBuilder(
                        "pngquant", "--force", "--output", optimized.toString(), "--speed", "1", "256", source.toString()
                ).inheritIO().start();
            } catch (IOException exception) {
                throw new GradleException(
                        "Modrinth PNG icon exceeds 262144 bytes and pngquant is unavailable: " + source,
                        exception
                );
            }
            try {
                if (process.waitFor() != 0) {
                    throw new GradleException("pngquant failed while optimizing Modrinth icon: " + source);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted while optimizing Modrinth icon", exception);
            }
            upload = optimized;
        }
        if (Files.size(upload) > MAX_ICON_BYTES) {
            throw new GradleException(
                    "Modrinth project icon is too large after optimization: " + Files.size(upload)
                            + " bytes, max " + MAX_ICON_BYTES + " bytes"
            );
        }
        return new PreparedIcon(upload, extension, contentType);
    }

    private void writeState(String status, String detail) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("status", status);
        state.addProperty("detail", detail);
        Path output = getStateFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, state + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String body) {
        var parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new GradleException("Expected a JSON object response from Modrinth, got: " + body);
        }
        return parsed.getAsJsonObject();
    }

    private static GradleException failure(String action, PublishingHttpClient.Response response) {
        return new GradleException(
                "Failed to " + action + ": HTTP " + response.status() + System.lineSeparator() + response.body()
        );
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record PreparedIcon(Path path, String extension, String contentType) {
    }
}
