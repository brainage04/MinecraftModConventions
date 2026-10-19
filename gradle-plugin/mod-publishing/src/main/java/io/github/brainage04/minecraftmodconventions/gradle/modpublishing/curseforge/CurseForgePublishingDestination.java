package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.curseforge;

import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.PublishingDestination;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** CurseForge file destination settings. */
public abstract class CurseForgePublishingDestination extends PublishingDestination {

    public abstract Property<String> getProjectId();

    public abstract Property<String> getProjectSlug();

    public abstract Property<String> getApiEndpoint();

    public abstract Property<Boolean> getClient();

    public abstract Property<Boolean> getServer();

    public abstract ListProperty<String> getJavaVersions();
}
