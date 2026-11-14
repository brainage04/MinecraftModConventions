package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth;

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
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Submits a draft Modrinth project for moderator review, the step that makes a new project public. */
@DisableCachingByDefault(because = "This task changes remote Modrinth state")
public abstract class SubmitModrinthProjectForReviewTask extends DefaultTask {
    private static final String DRAFT = "draft";
    // Submitting is setting status to "processing"; requested_status only picks the status an approved project gets.
    // https://docs.modrinth.com/api/operations/modifyproject/ and labrinth's project_edit (submit_for_review).
    private static final String PROCESSING = "processing";

    @Input
    public abstract Property<Boolean> getDryRun();

    @Input
    public abstract Property<String> getProjectId();

    @Input
    public abstract Property<String> getApiEndpoint();

    @Input
    public abstract Property<String> getUserAgent();

    @Input
    public abstract Property<Integer> getMaxRetries();

    @Internal
    public abstract Property<String> getToken();

    @Input
    public abstract Property<Boolean> getRequired();

    @Internal
    public abstract Property<String> getStepSummaryFile();

    /** Exists while a publishModrinth upload has started and not succeeded. */
    @Internal
    public abstract RegularFileProperty getUploadIncompleteMarker();

    @TaskAction
    void submit() throws IOException {
        String projectId = getProjectId().getOrElse("").trim();
        // publishModrinth runs this task as a finalizer even when its upload fails (Gradle user guide, "Finalizer
        // tasks"); submitting then would put the project up for review without the version that failed.
        if (getUploadIncompleteMarker().get().getAsFile().exists()) {
            getLogger().warn(
                    "Not submitting Modrinth project {} for review: the last Modrinth upload did not finish. "
                            + "Run publishModrinth again.",
                    projectId
            );
            return;
        }
        if (getDryRun().get()) {
            getLogger().lifecycle("Would submit Modrinth project {} for review if it is a draft", projectId);
            return;
        }
        if (projectId.isEmpty()) {
            throw new GradleException("Missing Modrinth project id while submitting the project for review.");
        }
        String token = getToken().getOrElse("").trim();
        if (token.isEmpty()) {
            throw new GradleException("Missing Modrinth token while submitting the project for review.");
        }
        PublishingHttpClient client = new PublishingHttpClient(
                getApiEndpoint().get(),
                token,
                getUserAgent().get(),
                getMaxRetries().get()
        );
        String projectPath = "/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        PublishingHttpClient.Response project = client.get(projectPath);
        if (project.status() != 200) {
            throw failure("fetch Modrinth project " + projectId, project);
        }
        JsonElement parsed = JsonParser.parseString(project.body());
        if (!parsed.isJsonObject()) {
            throw new GradleException("Expected a JSON object while fetching Modrinth project " + projectId);
        }
        String status = JsonValues.string(parsed.getAsJsonObject(), "status");
        if (!DRAFT.equals(status)) {
            getLogger().lifecycle("Modrinth project {} is {}, not a draft; not submitting it for review.", projectId, status);
            return;
        }
        JsonObject edit = new JsonObject();
        edit.addProperty("status", PROCESSING);
        PublishingHttpClient.Response submission = client.patchJson(projectPath, edit.toString());
        if (submission.status() != 204) {
            GradleException refusal = failure("submit Modrinth project " + projectId + " for review", submission);
            if (getRequired().get()) {
                throw refusal;
            }
            getLogger().warn(refusal.getMessage());
            String summary = getStepSummaryFile().getOrElse("");
            if (!summary.isBlank()) {
                Files.writeString(
                        Path.of(summary),
                        "- Modrinth: project " + projectId + " was not submitted for review: HTTP " + submission.status()
                                + " " + submission.body().replaceAll("\\s+", " ").strip() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
            return;
        }
        getLogger().lifecycle("Submitted Modrinth project {} for review.", projectId);
    }

    private static GradleException failure(String action, PublishingHttpClient.Response response) {
        return new GradleException(
                "Failed to " + action + ": HTTP " + response.status() + System.lineSeparator() + response.body()
        );
    }
}
