package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Registers {@value #TASK_NAME} on a mod project under {@code check}. It fails on a call to a
 * GameTest API that the game marks for removal, in every source set: a mock player belongs in a test,
 * but the method that joins one to the level goes away.
 */
public final class DeprecatedGameTestApiCheck {
    public static final String TASK_NAME = "checkDeprecatedGameTestApis";

    private DeprecatedGameTestApiCheck() {
    }

    /** Idempotent, so the Fabric and multi-loader conventions can both call it. */
    public static void register(Project project) {
        project.getPluginManager().withPlugin("java", _ -> {
            if (project.getTasks().getNames().contains(TASK_NAME)) {
                return;
            }
            Provider<Boolean> strictQuality = JavaQualityConventionsPlugin.strictQuality(project);
            TaskProvider<CheckDeprecatedGameTestApisTask> check = project.getTasks().register(
                    TASK_NAME,
                    CheckDeprecatedGameTestApisTask.class,
                    task -> {
                        task.setGroup("verification");
                        task.setDescription("Fails on GameTest APIs that Minecraft marks for removal.");
                        task.getRootDirectory().set(project.getRootDir().getAbsolutePath());
                        // The switch that turns the rest of the strict policy off turns this off too.
                        task.onlyIf(_ -> strictQuality.get());
                    }
            );
            project.getExtensions().getByType(SourceSetContainer.class)
                    .all(sourceSet -> check.configure(task -> task.getSources().from(sourceSet.getJava())));
            project.getTasks().named("check").configure(task -> task.dependsOn(check));
        });
    }
}
