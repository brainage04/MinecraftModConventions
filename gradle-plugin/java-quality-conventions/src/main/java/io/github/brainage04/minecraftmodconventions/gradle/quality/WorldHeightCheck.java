package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

import java.util.Set;

/** Registers {@value #TASK_NAME} on a mod project, over its {@code main} and {@code client} sources, under {@code check}. */
public final class WorldHeightCheck {
    public static final String TASK_NAME = "checkWorldHeight";

    /** GameTest and other source sets may build a level of a fixed height to set up a test. */
    private static final Set<String> MOD_SOURCE_SETS = Set.of("main", "client");

    private WorldHeightCheck() {
    }

    /** Idempotent, so the Fabric and multi-loader conventions can both call it. */
    public static void register(Project project) {
        project.getPluginManager().withPlugin("java", _ -> {
            if (project.getTasks().getNames().contains(TASK_NAME)) {
                return;
            }
            TaskProvider<CheckWorldHeightTask> check = project.getTasks().register(
                    TASK_NAME,
                    CheckWorldHeightTask.class,
                    task -> {
                        task.setGroup("verification");
                        task.setDescription("Reports literal overworld build limits in the mod's main and client "
                                + "sources.");
                        task.getRootDirectory().set(project.getRootDir().getAbsolutePath());
                        task.getReport().set(project.getLayout().getBuildDirectory().file("reports/world-height.txt"));
                    }
            );
            project.getExtensions().getByType(SourceSetContainer.class)
                    .matching(sourceSet -> MOD_SOURCE_SETS.contains(sourceSet.getName()))
                    .all(sourceSet -> check.configure(task -> task.getSources().from(sourceSet.getJava())));
            project.getTasks().named("check").configure(task -> task.dependsOn(check));
        });
    }
}
