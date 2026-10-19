package io.github.brainage04.minecraftmodconventions.gradle.central;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/** Configures shared Maven Central publication metadata and repository behavior. */
public abstract class MavenCentralPublishingExtension {
    public abstract Property<String> getRepository();

    public abstract Property<String> getPublicationName();

    public abstract Property<String> getDescription();

    public abstract Property<String> getNamespace();

    public abstract Property<String> getPublishingType();

    public abstract Property<String> getDeveloperId();

    public abstract Property<String> getDeveloperName();

    public abstract Property<String> getDeveloperUrl();

    public abstract Property<String> getLicenseName();

    public abstract Property<String> getLicenseUrl();

    public abstract DirectoryProperty getLocalRepository();
}
