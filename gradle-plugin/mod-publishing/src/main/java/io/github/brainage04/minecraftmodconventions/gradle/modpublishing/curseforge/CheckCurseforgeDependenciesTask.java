package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.curseforge;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.util.List;

/** Fails when a fabric.mod.json dependency has no CurseForge slug, before anything is published. */
@DisableCachingByDefault(because = "The check is cheap and has no output")
public abstract class CheckCurseforgeDependenciesTask extends DefaultTask {
    @Input
    public abstract ListProperty<String> getDependenciesWithoutSlug();

    @TaskAction
    void check() {
        requireSlugs(getDependenciesWithoutSlug().get());
    }

    public static void requireSlugs(List<String> withoutSlug) {
        if (!withoutSlug.isEmpty()) {
            throw new GradleException(
                    "No CurseForge slug for the fabric.mod.json dependencies " + String.join(", ", withoutSlug)
                            + "; set dependency_overrides.<mod id>.curseforge_slug in .modrinth/project.json,"
                            + " or curseforge_skip: true to leave a dependency off CurseForge."
            );
        }
    }
}
