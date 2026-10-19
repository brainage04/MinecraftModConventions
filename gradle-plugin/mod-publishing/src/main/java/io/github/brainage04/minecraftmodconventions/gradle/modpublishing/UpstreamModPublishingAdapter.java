package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.modmuss50.mpp.ModPublishExtension;
import me.modmuss50.mpp.platforms.curseforge.Curseforge;
import me.modmuss50.mpp.platforms.github.Github;
import me.modmuss50.mpp.platforms.modrinth.Modrinth;
import me.modmuss50.mpp.platforms.modrinth.ModrinthDependency;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth.ModrinthDependencyInference;
import org.gradle.api.JavaVersion;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;

/** Maps the conventions extension to the upstream Mod Publish Plugin model. */
final class UpstreamModPublishingAdapter {
    private UpstreamModPublishingAdapter() {
    }

    static Destinations configure(
            ModPublishingExtension extension,
            ModPublishExtension upstream
    ) {
        upstream.getFile().convention(extension.getReleaseJar());
        upstream.getVersion().convention(extension.getVersion());
        upstream.getDisplayName().convention(extension.getDisplayName());
        upstream.getChangelog().convention(extension.getChangelog());
        upstream.getType().convention(extension.getReleaseType());
        upstream.getModLoaders().convention(extension.getModLoaders());
        upstream.getDryRun().convention(extension.getDryRun());
        upstream.getMaxRetries().convention(extension.getMaxRetries());

        return new Destinations(
                upstream.github(destination -> configureGithub(extension, destination)),
                upstream.modrinth(destination -> configureModrinth(extension, destination)),
                upstream.curseforge(destination -> configureCurseforge(extension, destination))
        );
    }

    static void configureProjectMetadata(
            Project project,
            ModPublishingExtension extension,
            NamedDomainObjectProvider<Modrinth> modrinth
    ) {
        if (!extension.getSourceFabricModJson().isPresent()
                || !extension.getSourceFabricModJson().get().getAsFile().isFile()) {
            return;
        }
        String sourceJson = project.getProviders()
                .fileContents(extension.getSourceFabricModJson())
                .getAsText()
                .get();
        String configJson = extension.getModrinth().getProjectConfig().isPresent()
                ? project.getProviders().fileContents(extension.getModrinth().getProjectConfig()).getAsText().get()
                : "{}";
        ModrinthDependencyInference.Configuration configuration = ModrinthDependencyInference.parse(
                sourceJson,
                configJson
        );
        if (!configuration.projectSlug().isBlank()) {
            extension.getModrinth().getProjectSlug().convention(configuration.projectSlug());
        }
        if (!configuration.projectId().isBlank()) {
            extension.getModrinth().getProjectId().convention(configuration.projectId());
        }
        modrinth.configure(destination -> {
            if (!configuration.gameVersions().isEmpty()) {
                destination.getMinecraftVersions().convention(configuration.gameVersions());
            }
            if (!configuration.loaders().isEmpty()) {
                destination.getModLoaders().convention(configuration.loaders());
            }
            destination.getFeatured().convention(configuration.featured());
            for (ModrinthDependencyInference.Dependency inferred : configuration.dependencies()) {
                ModrinthDependency dependency = project.getObjects().newInstance(ModrinthDependency.class);
                dependency.getType().set(inferred.type());
                if (!inferred.projectId().isBlank()) {
                    dependency.getId().set(inferred.projectId());
                } else {
                    dependency.getSlug().set(inferred.projectSlug());
                }
                if (!inferred.version().isBlank()) {
                    dependency.getVersion().set(inferred.version());
                }
                destination.getDependencies().add(dependency);
            }
        });

        if (!extension.getModrinth().getIconFile().isPresent()) {
            JsonObject mod = JsonParser.parseString(sourceJson).getAsJsonObject();
            String icon = JsonValues.string(mod, "icon");
            if (!icon.isBlank()) {
                RegularFile iconFile = project.getLayout().getProjectDirectory().file("src/main/resources/" + icon);
                extension.getModrinth().getIconFile().convention(iconFile);
            }
        }
    }

    private static void configureGithub(ModPublishingExtension extension, Github destination) {
        destination.getAccessToken().convention(extension.getGithub().getToken().orElse(""));
        destination.getRepository().convention(extension.getGithub().getRepository().orElse(""));
        destination.getCommitish().convention(extension.getGithub().getCommitish().orElse(""));
        destination.getTagName().convention(extension.getReleaseTag());
        destination.getApiEndpoint().convention(extension.getGithub().getApiEndpoint());
    }

    private static void configureModrinth(ModPublishingExtension extension, Modrinth destination) {
        destination.getAccessToken().convention(extension.getModrinth().getToken().orElse(""));
        destination.getProjectId().convention(
                extension.getModrinth().getProjectId().orElse(
                        extension.getDryRun().map(dryRun -> dryRun ? "dryrun00" : "")
                )
        );
        destination.getMinecraftVersions().convention(extension.getMinecraftVersions());
        destination.getApiEndpoint().convention(extension.getModrinth().getApiEndpoint());
        destination.getFeatured().convention(true);
    }

    private static void configureCurseforge(ModPublishingExtension extension, Curseforge destination) {
        destination.getAccessToken().convention(extension.getCurseforge().getToken().orElse(""));
        destination.getProjectId().convention(extension.getCurseforge().getProjectId().orElse(""));
        destination.getProjectSlug().convention(extension.getCurseforge().getProjectSlug());
        destination.getMinecraftVersions().convention(extension.getMinecraftVersions());
        destination.getApiEndpoint().convention(extension.getCurseforge().getApiEndpoint());
        destination.getClient().convention(extension.getCurseforge().getClient());
        destination.getServer().convention(extension.getCurseforge().getServer());
        destination.getJavaVersions().addAll(extension.getCurseforge().getJavaVersions().map(
                versions -> versions.stream().map(JavaVersion::toVersion).toList()
        ));
    }

    record Destinations(
            NamedDomainObjectProvider<Github> github,
            NamedDomainObjectProvider<Modrinth> modrinth,
            NamedDomainObjectProvider<Curseforge> curseforge
    ) {
    }
}
