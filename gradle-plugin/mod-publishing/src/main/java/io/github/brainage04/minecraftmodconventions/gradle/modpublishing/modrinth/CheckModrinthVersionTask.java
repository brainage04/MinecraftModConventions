package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.JsonValues;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.http.PublishingHttpClient;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Prevents an exact Modrinth version from being uploaded more than once. */
@DisableCachingByDefault(because = "Modrinth version existence is remote state")
public abstract class CheckModrinthVersionTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getDestinationEnabled();

    @Input
    public abstract Property<Boolean> getDryRun();

    @Input
    public abstract Property<String> getProjectId();

    @Input
    public abstract Property<String> getVersion();

    @Input
    public abstract Property<String> getApiEndpoint();

    @Input
    public abstract Property<String> getUserAgent();

    @Input
    public abstract Property<Integer> getMaxRetries();

    @Internal
    public abstract Property<String> getToken();

    @OutputFile
    public abstract RegularFileProperty getStateFile();

    @TaskAction
    void checkVersion() throws IOException {
        if (!getDestinationEnabled().get()) {
            writeState("disabled", true);
            return;
        }
        if (getDryRun().get()) {
            writeState("dry-run", true);
            return;
        }
        String token = getToken().getOrElse("").trim();
        if (token.isEmpty()) {
            throw new GradleException("Missing Modrinth token while checking version idempotency.");
        }
        String projectId = getProjectId().getOrElse("").trim();
        if (projectId.isEmpty()) {
            throw new GradleException("Missing Modrinth project id while checking version idempotency.");
        }
        PublishingHttpClient client = new PublishingHttpClient(
                getApiEndpoint().get(),
                token,
                getUserAgent().get(),
                getMaxRetries().get()
        );
        String encodedProject = URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        PublishingHttpClient.Response response = client.get("/project/" + encodedProject + "/version");
        if (response.status() != 200) {
            throw new GradleException(
                    "Failed to list Modrinth versions for " + projectId + ": HTTP " + response.status()
                            + System.lineSeparator() + response.body()
            );
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonArray()) {
            throw new GradleException("Expected a JSON array while listing Modrinth versions.");
        }
        String version = getVersion().get();
        boolean exists = false;
        JsonArray versions = parsed.getAsJsonArray();
        for (JsonElement candidate : versions) {
            if (candidate.isJsonObject()
                    && version.equals(JsonValues.string(candidate.getAsJsonObject(), "version_number"))) {
                exists = true;
                break;
            }
        }
        writeState(exists ? "exists" : "missing", !exists);
        if (exists) {
            getLogger().lifecycle("Modrinth version {} already exists; skipping upload.", version);
        }
    }

    private void writeState(String status, boolean shouldPublish) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("status", status);
        state.addProperty("shouldPublish", shouldPublish);
        var file = getStateFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                new GsonBuilder().setPrettyPrinting().create().toJson(state) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    public static boolean shouldPublish(java.io.File file) {
        if (!file.isFile()) {
            return true;
        }
        try {
            JsonObject state = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
            return state.get("shouldPublish").getAsBoolean();
        } catch (IOException exception) {
            throw new GradleException("Failed to read publication preflight state " + file, exception);
        }
    }
}
