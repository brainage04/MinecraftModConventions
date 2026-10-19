package io.github.brainage04.minecraftmodconventions.gradle.central;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.util.Set;

/** Validates credentials, signing, and Central release settings without contacting Central. */
@DisableCachingByDefault(because = "Validation tasks have no reusable output")
public abstract class ValidateMavenCentralPublishingTask extends DefaultTask {
    private static final Set<String> SUPPORTED_PUBLISHING_TYPES = Set.of(
            "user_managed",
            "automatic",
            "portal_api"
    );

    @Input
    public abstract Property<String> getNamespace();

    @Input
    public abstract Property<String> getPublishingType();

    @Internal
    public abstract Property<String> getCentralPortalUsername();

    @Internal
    public abstract Property<String> getCentralPortalPassword();

    @Internal
    public abstract Property<String> getSigningKey();

    @Internal
    public abstract Property<String> getSigningPassword();

    @Input
    public abstract Property<Boolean> getUseGpgAgentSigning();

    @TaskAction
    public void validateCentralConfiguration() {
        String publishingType = ValidateMavenPublicationMetadataTask.requiredValue(
                getPublishingType().getOrNull(),
                "publishingType"
        );
        if (!SUPPORTED_PUBLISHING_TYPES.contains(publishingType)) {
            throw new GradleException(
                    "Unsupported centralPublishingType '" + publishingType
                            + "'. Use one of: user_managed, automatic, portal_api."
            );
        }
        ValidateMavenPublicationMetadataTask.requiredValue(getNamespace().getOrNull(), "namespace");
        if (!getCentralPortalUsername().isPresent() || !getCentralPortalPassword().isPresent()) {
            throw new GradleException(
                    "Set CENTRAL_PORTAL_USERNAME and CENTRAL_PORTAL_PASSWORD before publishing to Maven Central."
            );
        }
        if (!getUseGpgAgentSigning().get()
                && (!getSigningKey().isPresent() || !getSigningPassword().isPresent())) {
            throw missingSigningConfiguration();
        }
    }

    static GradleException missingSigningConfiguration() {
        return new GradleException(
                "Use -PuseGpgAgentSigning=true for local GPG-agent signing, or set SIGNING_KEY and "
                        + "SIGNING_PASSWORD for in-memory signing."
        );
    }
}
