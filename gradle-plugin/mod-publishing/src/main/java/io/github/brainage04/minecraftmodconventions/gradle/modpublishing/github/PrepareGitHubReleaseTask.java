package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.github;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.http.PublishingHttpClient;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.JsonValues;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Finds a GitHub release by tag and skips an asset whose exact file name already exists. */
@DisableCachingByDefault(because = "GitHub releases and assets are remote state")
public abstract class PrepareGitHubReleaseTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getDestinationEnabled();

    @Input
    public abstract Property<Boolean> getDryRun();

    @Input
    public abstract Property<String> getRepository();

    @Input
    public abstract Property<String> getReleaseTag();

    @Input
    public abstract Property<String> getReleaseFileName();

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

    @OutputFile
    @Optional
    public abstract RegularFileProperty getExistingReleaseFile();

    @TaskAction
    void prepareRelease() throws IOException {
        Files.deleteIfExists(getExistingReleaseFile().get().getAsFile().toPath());
        if (!getDestinationEnabled().get()) {
            writeState("disabled", true);
            return;
        }
        if (getDryRun().get()) {
            writeState("dry-run", true);
            return;
        }
        String repository = getRepository().getOrElse("").trim();
        String token = getToken().getOrElse("").trim();
        if (repository.isEmpty()) {
            throw new GradleException("Missing GitHub repository while checking release idempotency.");
        }
        if (token.isEmpty()) {
            throw new GradleException("Missing GitHub token while checking release idempotency.");
        }
        PublishingHttpClient client = new PublishingHttpClient(
                getApiEndpoint().get(),
                token,
                getUserAgent().get(),
                getMaxRetries().get()
        );
        String encodedTag = URLEncoder.encode(getReleaseTag().get(), StandardCharsets.UTF_8).replace("+", "%20");
        PublishingHttpClient.Response response = client.get(
                "/repos/" + repository + "/releases/tags/" + encodedTag
        );
        if (response.status() == 404) {
            writeState("missing", true);
            return;
        }
        if (response.status() != 200) {
            throw new GradleException(
                    "Failed to find GitHub release " + getReleaseTag().get() + ": HTTP " + response.status()
                            + System.lineSeparator() + response.body()
            );
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            throw new GradleException("Expected a JSON object while finding a GitHub release.");
        }
        JsonObject release = parsed.getAsJsonObject();
        long releaseId = requiredLong(release, "id");
        String url = JsonValues.string(release, "html_url");
        String title = JsonValues.string(release, "name");
        if (title.isBlank()) {
            title = getReleaseTag().get();
        }
        writeExistingRelease(releaseId, url, title);

        String fileName = getReleaseFileName().get();
        boolean assetExists = false;
        JsonElement assets = release.get("assets");
        if (assets != null && assets.isJsonArray()) {
            for (JsonElement asset : assets.getAsJsonArray()) {
                if (asset.isJsonObject()
                        && fileName.equals(JsonValues.string(asset.getAsJsonObject(), "name"))) {
                    assetExists = true;
                    break;
                }
            }
        }
        writeState(assetExists ? "asset-exists" : "release-exists", !assetExists);
        if (assetExists) {
            getLogger().lifecycle("GitHub release asset {} already exists; skipping upload.", fileName);
        }
    }

    private void writeExistingRelease(long releaseId, String url, String title) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("type", "github");
        result.addProperty("repository", getRepository().get());
        result.addProperty("releaseId", releaseId);
        result.addProperty("url", url);
        result.addProperty("title", title);
        var file = getExistingReleaseFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                new GsonBuilder().setPrettyPrinting().create().toJson(result) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
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

    private static long requiredLong(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new GradleException("Missing " + name + " in GitHub release response.");
        }
        return element.getAsLong();
    }
}
