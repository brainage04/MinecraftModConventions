package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.repository;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Decides whether this project may be published to Modrinth and CurseForge.
 *
 * <p>A mod in a private GitHub repository is never published to the stores, while its GitHub release still happens.
 * Nothing local records repository visibility, so it comes from the GitHub API; every answer this task cannot read
 * refuses store publication instead of guessing, and the log names the reason.
 */
@DisableCachingByDefault(because = "GitHub repository visibility is remote state")
public abstract class CheckRepositoryVisibilityTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getStoreDestinationEnabled();

    @Input
    public abstract Property<Boolean> getDryRun();

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

    @Input
    public abstract Property<Long> getBuildStartMillis();

    @OutputFile
    public abstract RegularFileProperty getStateFile();

    @TaskAction
    void checkVisibility() throws IOException {
        if (!getStoreDestinationEnabled().get()) {
            writeState("stores-disabled", false);
            return;
        }
        if (getDryRun().get()) {
            writeState("dry-run", true);
            return;
        }
        String repository = getRepository().getOrElse("").trim();
        if (repository.isEmpty()) {
            refuse(
                    "unknown",
                    "no GitHub repository is configured",
                    "Set modPublishing.github.repository or GITHUB_REPOSITORY."
            );
            return;
        }
        String token = getToken().getOrElse("").trim();
        // PublishingHttpClient sends Authorization verbatim, and the GitHub API reads a token only with a scheme.
        PublishingHttpClient client = new PublishingHttpClient(
                getApiEndpoint().get(),
                token.isEmpty() ? "" : "Bearer " + token,
                getUserAgent().get(),
                getMaxRetries().get()
        );
        PublishingHttpClient.Response response;
        try {
            response = client.get("/repos/" + repository);
        } catch (GradleException exception) {
            refuse(
                    "unknown",
                    "the GitHub API request failed (" + exception.getMessage() + ")",
                    "Check the GitHub API endpoint and the network."
            );
            return;
        }
        if (response.status() == 404) {
            refuse(
                    "unreadable",
                    token.isEmpty()
                            ? "GitHub does not show " + repository + " to an unauthenticated request"
                            : "the GitHub token cannot see repository " + repository,
                    token.isEmpty()
                            ? "Set modPublishing.github.token or GITHUB_TOKEN so the visibility can be read."
                            : "Check the repository name and the token's access to it."
            );
            return;
        }
        if (response.status() != 200) {
            refuse(
                    "unknown",
                    "GitHub returned HTTP " + response.status() + " for " + repository,
                    "Check the GitHub API endpoint."
            );
            return;
        }
        Boolean isPrivate = privateFlag(response.body());
        if (isPrivate == null) {
            refuse(
                    "unknown",
                    "the GitHub response for " + repository + " has no private flag",
                    "Check the GitHub API endpoint."
            );
            return;
        }
        if (isPrivate) {
            writeState("private", false);
            getLogger().lifecycle(
                    "Skipping Modrinth and CurseForge publication: GitHub repository {} is private. A mod is "
                            + "published to the stores only once its repository is public; its GitHub release still "
                            + "happens.",
                    repository
            );
            return;
        }
        writeState("public", true);
    }

    private static Boolean privateFlag(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException exception) {
            return null;
        }
        JsonElement visibility = parsed.isJsonObject() ? parsed.getAsJsonObject().get("private") : null;
        if (visibility == null || !visibility.isJsonPrimitive() || !visibility.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return visibility.getAsBoolean();
    }

    private void refuse(String status, String reason, String fix) throws IOException {
        writeState(status, false);
        getLogger().warn(
                "Skipping Modrinth and CurseForge publication because it cannot be checked that this repository may "
                        + "publish to the stores: {}. A private repository must not publish there, so the stores are "
                        + "skipped rather than guessed at. {} The GitHub release still happens.",
                reason,
                fix
        );
    }

    private void writeState(String status, boolean allowed) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("status", status);
        state.addProperty("allowed", allowed);
        state.addProperty("checkedAt", System.currentTimeMillis());
        var file = getStateFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                new GsonBuilder().setPrettyPrinting().create().toJson(state) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    /**
     * Whether the state file written by this task allows store publication.
     *
     * <p>Only a state file this task wrote with {@code "allowed": true} allows publication. A missing file means the
     * check did not run, which happens when the task is excluded by hand, and an unreadable file means its answer is
     * unknown; both refuse the stores, because the release must not publish there on a guess.
     */
    public static boolean storePublicationAllowed(
            org.gradle.api.logging.Logger logger,
            long buildStartMillis,
            File file
    ) {
        if (!file.isFile()) {
            logger.warn(
                    "Skipping Modrinth and CurseForge publication: {} is missing, so it was not checked whether this "
                            + "repository may publish to the stores. A private repository must not publish there, so "
                            + "the stores are skipped rather than guessed at. The GitHub release still happens.",
                    file
            );
            return false;
        }
        try {
            JsonObject state = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
            JsonElement allowed = state.get("allowed");
            JsonElement checkedAt = state.get("checkedAt");
            if (allowed == null || !allowed.isJsonPrimitive() || !allowed.getAsBoolean()) {
                return false;
            }
            // A file an earlier build wrote reports nothing about this one, which is what an excluded check leaves.
            if (checkedAt == null
                    || !checkedAt.isJsonPrimitive()
                    || checkedAt.getAsLong() < buildStartMillis) {
                logger.warn(
                        "Skipping Modrinth and CurseForge publication: {} was written by an earlier build, so this "
                                + "build has not checked whether the repository may publish to the stores. A private "
                                + "repository must not publish there, so the stores are skipped rather than guessed "
                                + "at. The GitHub release still happens.",
                        file
                );
                return false;
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            logger.warn(
                    "Skipping Modrinth and CurseForge publication: {} cannot be read, so it was not checked whether "
                            + "this repository may publish to the stores. A private repository must not publish there, "
                            + "so the stores are skipped rather than guessed at. The GitHub release still happens.",
                    file
            );
            return false;
        }
    }
}
