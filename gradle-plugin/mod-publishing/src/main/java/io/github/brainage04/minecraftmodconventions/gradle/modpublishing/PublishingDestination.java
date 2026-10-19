package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import org.gradle.api.provider.Property;

/** Common opt-in and credential settings for one mod distribution destination. */
public abstract class PublishingDestination {

    public abstract Property<Boolean> getEnabled();

    public abstract Property<String> getToken();
}
