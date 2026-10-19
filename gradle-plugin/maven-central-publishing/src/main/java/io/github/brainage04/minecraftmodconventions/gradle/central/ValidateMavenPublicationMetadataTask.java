package io.github.brainage04.minecraftmodconventions.gradle.central;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Validates the reusable metadata required by every Maven publication. */
@DisableCachingByDefault(because = "Validation tasks have no reusable output")
public abstract class ValidateMavenPublicationMetadataTask extends DefaultTask {
    @Input
    public abstract Property<String> getPublicationName();

    @Input
    public abstract Property<String> getPublicationDescription();

    @Input
    public abstract Property<String> getRepository();

    @Input
    public abstract Property<String> getDeveloperId();

    @Input
    public abstract Property<String> getDeveloperName();

    @Input
    public abstract Property<String> getDeveloperUrl();

    @Input
    public abstract Property<String> getLicenseName();

    @Input
    public abstract Property<String> getLicenseUrl();

    @TaskAction
    public void validateMetadata() {
        requiredValue(getPublicationName().getOrNull(), "publicationName");
        requiredValue(getPublicationDescription().getOrNull(), "description");
        normalizedRepository(requiredValue(getRepository().getOrNull(), "repository"));
        requiredValue(getDeveloperId().getOrNull(), "developerId");
        requiredValue(getDeveloperName().getOrNull(), "developerName");
        requiredValue(getDeveloperUrl().getOrNull(), "developerUrl");
        requiredValue(getLicenseName().getOrNull(), "licenseName");
        requiredValue(getLicenseUrl().getOrNull(), "licenseUrl");
    }

    static String requiredValue(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new GradleException("mavenCentralPublishing." + property + " must not be blank.");
        }
        return value.strip();
    }

    static String normalizedRepository(String repository) {
        String normalized = repository.strip();
        String[] parts = normalized.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new GradleException(
                    "mavenCentralPublishing.repository must use nonblank owner/name notation, but was '"
                            + repository + "'."
            );
        }
        return parts[0] + "/" + parts[1];
    }
}
