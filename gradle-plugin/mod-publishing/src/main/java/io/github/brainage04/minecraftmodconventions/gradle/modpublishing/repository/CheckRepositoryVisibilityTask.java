package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.http.PublishingHttpClient;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

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

    @Internal
    public abstract Property<StoreVisibility> getVisibility();

    @TaskAction
    void checkVisibility() {
        if (!getStoreDestinationEnabled().get()) {
            getVisibility().get().record(false);
            return;
        }
        if (getDryRun().get()) {
            getVisibility().get().record(true);
            return;
        }
        String repository = getRepository().getOrElse("").trim();
        if (repository.isEmpty()) {
            refuse("no GitHub repository is configured. Set modPublishing.github.repository or GITHUB_REPOSITORY.");
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
            refuse("the GitHub API request for " + repository + " failed (" + exception.getMessage()
                    + "). Check the GitHub API endpoint and the network.");
            return;
        }
        if (response.status() == 404) {
            refuse(token.isEmpty()
                    ? "GitHub does not show " + repository + " to an unauthenticated request. Set "
                            + "modPublishing.github.token or GITHUB_TOKEN so its visibility can be read."
                    : "the GitHub token cannot see repository " + repository
                            + ". Check the repository name and the token's access to it.");
            return;
        }
        if (response.status() != 200) {
            refuse("GitHub returned HTTP " + response.status() + " for " + repository
                    + ". Check the GitHub API endpoint.");
            return;
        }
        Boolean isPrivate = privateFlag(response.body());
        if (isPrivate == null) {
            refuse("the GitHub response for " + repository
                    + " has no private flag. Check the GitHub API endpoint.");
            return;
        }
        if (isPrivate) {
            getVisibility().get().record(false);
            getLogger().lifecycle(
                    "Skipping Modrinth and CurseForge publication: GitHub repository {} is private. A mod is "
                            + "published to the stores only once its repository is public; its GitHub release still "
                            + "happens.",
                    repository
            );
            return;
        }
        getVisibility().get().record(true);
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

    private void refuse(String reason) {
        getVisibility().get().record(false);
        getLogger().warn(
                "Skipping Modrinth and CurseForge publication because it cannot be checked that this repository may "
                        + "publish to the stores: {}. A private repository must not publish there, so the stores are "
                        + "skipped rather than guessed at. The GitHub release still happens.",
                reason
        );
    }
}
