package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.curseforge.CurseForgePublishingDestination;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.github.GitHubPublishingDestination;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth.ModrinthPublishingDestination;
import me.modmuss50.mpp.ReleaseType;
import org.gradle.api.Action;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** Shared release metadata and explicit destination opt-ins for mod distribution. */
public abstract class ModPublishingExtension {
    private final GitHubPublishingDestination github;
    private final ModrinthPublishingDestination modrinth;
    private final CurseForgePublishingDestination curseforge;

    @Inject
    public ModPublishingExtension(ObjectFactory objects) {
        github = objects.newInstance(GitHubPublishingDestination.class);
        modrinth = objects.newInstance(ModrinthPublishingDestination.class);
        curseforge = objects.newInstance(CurseForgePublishingDestination.class);
    }

    public abstract Property<String> getVersion();

    public abstract Property<String> getReleaseTag();

    public abstract Property<String> getDisplayName();

    public abstract Property<String> getChangelog();

    public abstract Property<ReleaseType> getReleaseType();

    public abstract Property<Boolean> getPrerelease();

    public abstract ListProperty<String> getMinecraftVersions();

    public abstract ListProperty<String> getModLoaders();

    public abstract RegularFileProperty getReleaseJar();

    public abstract RegularFileProperty getFabricModJson();

    public abstract RegularFileProperty getSourceFabricModJson();

    public abstract RegularFileProperty getLicenseFile();

    public abstract Property<Boolean> getDryRun();

    public abstract Property<Integer> getMaxRetries();

    public GitHubPublishingDestination getGithub() {
        return github;
    }

    public void github(Action<? super GitHubPublishingDestination> action) {
        github.getEnabled().set(true);
        action.execute(github);
    }

    public ModrinthPublishingDestination getModrinth() {
        return modrinth;
    }

    public void modrinth(Action<? super ModrinthPublishingDestination> action) {
        modrinth.getEnabled().set(true);
        action.execute(modrinth);
    }

    public CurseForgePublishingDestination getCurseforge() {
        return curseforge;
    }

    public void curseforge(Action<? super CurseForgePublishingDestination> action) {
        curseforge.getEnabled().set(true);
        action.execute(curseforge);
    }
}
