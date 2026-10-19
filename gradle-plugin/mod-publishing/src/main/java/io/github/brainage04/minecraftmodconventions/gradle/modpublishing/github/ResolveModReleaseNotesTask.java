package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.github;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.http.PublishingHttpClient;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.JsonValues;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves explicit, annotated-tag, or GitHub-generated release notes in that precedence order. */
@DisableCachingByDefault(because = "Git tag and generated GitHub release notes are external state")
public abstract class ResolveModReleaseNotesTask extends DefaultTask {
    @Input
    public abstract Property<String> getReleaseTag();

    @Input
    @Optional
    public abstract Property<String> getExplicitNotes();

    @Input
    public abstract Property<String> getRepository();

    @Input
    public abstract Property<String> getApiEndpoint();

    @Input
    public abstract Property<String> getUserAgent();

    @Input
    public abstract Property<Integer> getMaxRetries();

    @Internal
    public abstract Property<String> getToken();

    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void resolveNotes() throws IOException {
        String notes = getExplicitNotes().getOrElse("");
        if (notes.isBlank()) {
            notes = annotatedTagNotes(getReleaseTag().get());
        }
        if (notes.isBlank()) {
            notes = generatedReleaseNotes();
        }
        Path output = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, notes.strip() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private String annotatedTagNotes(String tag) throws IOException {
        Process process = new ProcessBuilder(
                "git",
                "for-each-ref",
                "--format=%(contents)",
                "refs/tags/" + tag
        ).directory(getRepositoryDirectory().get().getAsFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int status = process.waitFor();
            if (status != 0) {
                throw new GradleException("Failed to read annotated tag " + tag + ": " + stderr.strip());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while reading annotated tag " + tag, exception);
        }
        return stdout.strip();
    }

    private String generatedReleaseNotes() {
        String repository = getRepository().getOrElse("").trim();
        String token = getToken().getOrElse("").trim();
        if (repository.isEmpty()) {
            throw new GradleException("Missing GitHub repository while generating release notes.");
        }
        if (token.isEmpty()) {
            throw new GradleException("Missing GITHUB_TOKEN while generating release notes.");
        }
        JsonObject request = new JsonObject();
        request.addProperty("tag_name", getReleaseTag().get());
        PublishingHttpClient client = new PublishingHttpClient(
                getApiEndpoint().get(),
                token,
                getUserAgent().get(),
                getMaxRetries().get()
        );
        PublishingHttpClient.Response response = client.postJson(
                "/repos/" + repository + "/releases/generate-notes",
                new Gson().toJson(request)
        );
        if (response.status() != 200) {
            throw new GradleException(
                    "Failed to generate GitHub release notes: HTTP " + response.status()
                            + System.lineSeparator() + response.body()
            );
        }
        var parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            throw new GradleException("Expected a JSON object from GitHub generated release notes.");
        }
        String body = JsonValues.string(parsed.getAsJsonObject(), "body");
        if (body.isBlank()) {
            throw new GradleException("GitHub generated release notes returned an empty body.");
        }
        return body;
    }
}
