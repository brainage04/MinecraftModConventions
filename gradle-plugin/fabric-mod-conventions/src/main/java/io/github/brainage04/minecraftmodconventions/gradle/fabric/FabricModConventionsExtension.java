package io.github.brainage04.minecraftmodconventions.gradle.fabric;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.List;

/** Typed switches and resource inputs for the shared Fabric mod conventions. */
public abstract class FabricModConventionsExtension {
    private final Property<Boolean> repositoriesEnabled;
    private final Property<Boolean> javaEnabled;
    private final Property<Boolean> sourcesJarEnabled;
    private final Property<Boolean> resourceExpansionEnabled;
    private final Property<Boolean> licenseJarEnabled;
    private final ListProperty<String> fabricModJsonProperties;
    private final ListProperty<String> additionalFabricModJsonProperties;
    private final RegularFileProperty licenseFile;

    @Inject
    public FabricModConventionsExtension(ObjectFactory objects, ProjectLayout layout) {
        repositoriesEnabled = objects.property(Boolean.class).convention(true);
        javaEnabled = objects.property(Boolean.class).convention(true);
        sourcesJarEnabled = objects.property(Boolean.class).convention(true);
        resourceExpansionEnabled = objects.property(Boolean.class).convention(true);
        licenseJarEnabled = objects.property(Boolean.class).convention(true);
        fabricModJsonProperties = objects.listProperty(String.class).convention(List.of(
                "mod_id",
                "mod_version",
                "mod_name",
                "loader_version",
                "minecraft_version",
                "java_version"
        ));
        additionalFabricModJsonProperties = objects.listProperty(String.class).convention(List.of());
        licenseFile = objects.fileProperty().convention(layout.getProjectDirectory().file("LICENSE"));
    }

    public Property<Boolean> getRepositoriesEnabled() {
        return repositoriesEnabled;
    }

    public Property<Boolean> getJavaEnabled() {
        return javaEnabled;
    }

    public Property<Boolean> getSourcesJarEnabled() {
        return sourcesJarEnabled;
    }

    public Property<Boolean> getResourceExpansionEnabled() {
        return resourceExpansionEnabled;
    }

    public Property<Boolean> getLicenseJarEnabled() {
        return licenseJarEnabled;
    }

    public ListProperty<String> getFabricModJsonProperties() {
        return fabricModJsonProperties;
    }

    public ListProperty<String> getAdditionalFabricModJsonProperties() {
        return additionalFabricModJsonProperties;
    }

    public RegularFileProperty getLicenseFile() {
        return licenseFile;
    }
}
