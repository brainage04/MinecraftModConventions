package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.github;

import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.PublishingDestination;
import org.gradle.api.provider.Property;

/** GitHub release destination settings. */
public abstract class GitHubPublishingDestination extends PublishingDestination {

    public abstract Property<String> getRepository();

    public abstract Property<String> getCommitish();

    public abstract Property<String> getApiEndpoint();
}
