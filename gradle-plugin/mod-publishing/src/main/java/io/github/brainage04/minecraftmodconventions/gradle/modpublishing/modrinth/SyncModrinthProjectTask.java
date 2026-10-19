package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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

/** Creates a missing Modrinth project and synchronizes its mutable project metadata. */
@DisableCachingByDefault(because = "This task synchronizes remote Modrinth state")
public abstract class SyncModrinthProjectTask extends DefaultTask {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
    public abstract RegularFileProperty getProjectStateFile();

    @TaskAction
    void syncProject() throws IOException {
        JsonObject metadata = PrepareModrinthProjectMetadataTask.readObject(
                getMetadataFile().get().getAsFile().toPath()
        );
        String slug = required(JsonValues.string(metadata, "slug"), "Modrinth project slug");
        boolean created;
        String projectId;

        if (getDryRun().get()) {
            created = false;
            projectId = firstNonBlank(
                    JsonValues.string(metadata, "project_id"),
                    "dryrun00"
            );
            getLogger().lifecycle("Would synchronize Modrinth project {}", slug);
        } else {
            String token = required(getToken().getOrElse(""), "MODRINTH_TOKEN");
            PublishingHttpClient client = new PublishingHttpClient(
                    getApiEndpoint().get(),
                    token,
                    getUserAgent().get(),
                    getMaxRetries().get()
            );
            String projectPath = "/project/" + pathSegment(slug);
            PublishingHttpClient.Response projectResponse = client.get(projectPath);
            if (projectResponse.status() == 200) {
                created = false;
                projectId = required(
                        JsonValues.string(parseObject(projectResponse.body()), "id"),
                        "Modrinth project response id"
                );
                getLogger().lifecycle("Modrinth project already exists: {}", projectId);
            } else if (projectResponse.status() == 404) {
                created = true;
                JsonObject create = metadata.getAsJsonObject("create");
                byte[] icon = getIconFile().isPresent()
                        ? Files.readAllBytes(getIconFile().get().getAsFile().toPath())
                        : null;
                String iconName = getIconFile().isPresent() ? getIconFile().get().getAsFile().getName() : null;
                String iconType = getIconFile().isPresent()
                        ? SyncModrinthIconTask.contentType(getIconFile().get().getAsFile().toPath())
                        : null;
                PublishingHttpClient.MultipartBody body = PublishingHttpClient.multipartJson(
                        GSON.toJson(create),
                        "icon",
                        iconName,
                        iconType,
                        icon
                );
                PublishingHttpClient.Response createResponse = client.postMultipart("/project", body);
                expect(createResponse, 200, "create Modrinth project " + slug);
                projectId = required(
                        JsonValues.string(parseObject(createResponse.body()), "id"),
                        "created Modrinth project id"
                );
                getLogger().lifecycle("Created Modrinth project: {}", projectId);
            } else {
                throw responseFailure("fetch Modrinth project " + slug, projectResponse);
            }

            PublishingHttpClient.Response updateResponse = client.patchJson(
                    projectPath,
                    GSON.toJson(metadata.getAsJsonObject("update"))
            );
            expect(updateResponse, 204, "synchronize Modrinth project metadata for " + slug);
        }

        JsonObject state = new JsonObject();
        state.addProperty("id", projectId);
        state.addProperty("slug", slug);
        state.addProperty("created", created);
        Path statePath = getProjectStateFile().get().getAsFile().toPath();
        Files.createDirectories(statePath.getParent());
        Files.writeString(statePath, GSON.toJson(state) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String body) {
        var value = com.google.gson.JsonParser.parseString(body);
        if (!value.isJsonObject()) {
            throw new GradleException("Expected a JSON object response from Modrinth, got: " + body);
        }
        return value.getAsJsonObject();
    }

    private static void expect(PublishingHttpClient.Response response, int expected, String action) {
        if (response.status() != expected) {
            throw responseFailure(action, response);
        }
    }

    private static GradleException responseFailure(String action, PublishingHttpClient.Response response) {
        return new GradleException(
                "Failed to " + action + ": HTTP " + response.status() + System.lineSeparator() + response.body()
        );
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new GradleException("Missing " + name);
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
