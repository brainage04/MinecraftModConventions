package io.github.brainage04.minecraftmodconventions.gradle.central;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Finalizes a non-empty staged deployment through the Sonatype Central Portal. */
@DisableCachingByDefault(because = "The task mutates remote Central deployment state")
public abstract class FinalizeMavenCentralDeploymentTask extends DefaultTask {
    private static final String CENTRAL_UPLOAD_BASE_URL =
            "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/";

    @Input
    public abstract Property<String> getNamespace();

    @Input
    public abstract Property<String> getPublishingType();

    @Internal
    public abstract Property<String> getCentralPortalUsername();

    @Internal
    public abstract Property<String> getCentralPortalPassword();

    @Internal
    public abstract ListProperty<String> getPublicationTaskPaths();

    @TaskAction
    public void finalizeDeployment() {
        boolean publicationUploaded = getPublicationTaskPaths().get().stream()
                .map(path -> getProject().getTasks().getByPath(path))
                .map(Task::getState)
                .anyMatch(state -> state.getFailure() == null && state.getDidWork());
        if (!publicationUploaded) {
            throw new GradleException(
                    "No Maven publication was uploaded to the Central staging repository; refusing to finalize an empty deployment."
            );
        }

        String namespace = getNamespace().get().strip();
        String publishingType = getPublishingType().get().strip();
        String bearerToken = Base64.getEncoder().encodeToString(
                (getCentralPortalUsername().get() + ":" + getCentralPortalPassword().get())
                        .getBytes(StandardCharsets.UTF_8)
        );
        String uploadUrl = CENTRAL_UPLOAD_BASE_URL
                + namespace
                + "?publishing_type="
                + URLEncoder.encode(publishingType, StandardCharsets.UTF_8);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(uploadUrl).toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            String responseText = readResponse(connection, responseCode);
            if (responseCode < 200 || responseCode >= 300) {
                throw new GradleException(
                        "Central upload request failed with HTTP " + responseCode + ": " + responseText
                );
            }
            String response = responseText.isBlank() ? Integer.toString(responseCode) : responseText;
            getLogger().lifecycle(
                    "Central deployment uploaded for namespace '{}'. Response: {}",
                    namespace,
                    response
            );
        } catch (IOException exception) {
            throw new GradleException("Central upload request failed: " + exception.getMessage(), exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readResponse(HttpURLConnection connection, int responseCode) throws IOException {
        InputStream responseStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (responseStream == null) {
            return "";
        }
        try (responseStream) {
            return new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
