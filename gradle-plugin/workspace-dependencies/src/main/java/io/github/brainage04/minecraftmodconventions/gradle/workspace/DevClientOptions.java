package io.github.brainage04.minecraftmodconventions.gradle.workspace;

import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copies one machine-wide Minecraft {@code options.txt} into the development client's run directory every time
 * {@code runClient} starts, so every mod on the machine launches with the same keybinds, video and audio settings.
 * The file is the source of truth: changes made in the development client are overwritten on the next launch.
 */
final class DevClientOptions {
    /** Gradle property naming the options file; set it in {@code ~/.gradle/gradle.properties} to opt in. */
    static final String PROPERTY = "minecraftmodconventions.devClientOptions";
    private static final String RUN_CLIENT_TASK = "runClient";
    private static final String CLIENT_RUN = "client";
    private static final String OPTIONS_FILE = "options.txt";

    private DevClientOptions() {
    }

    static void configure(Project project) {
        Provider<String> configured = project.getProviders().gradleProperty(PROPERTY);
        project.getTasks().withType(JavaExec.class)
                .matching(task -> task.getName().equals(RUN_CLIENT_TASK))
                .configureEach(task -> {
                    if (configured.isPresent()) {
                        File source = new File(configured.get());
                        Provider<File> runDirectory = project.provider(() -> clientRunDirectory(project, task));
                        task.doFirst("copyDevClientOptions", runTask -> copy(runTask, source, runDirectory.get()));
                    }
                });
    }

    /**
     * Loom only points a run task's working directory at its run directory when the task executes, so read the
     * directory from the Loom {@code client} run instead. Fabric Loom and Architectury Loom both expose it through
     * {@code loom.runs.client.runDir}, but as different classes, hence the reflection. Without Loom, the task's own
     * working directory is the run directory.
     */
    private static File clientRunDirectory(Project project, JavaExec task) {
        Object loom = project.getExtensions().findByName("loom");
        if (loom == null) {
            return task.getWorkingDir();
        }
        try {
            Object runs = loom.getClass().getMethod("getRuns").invoke(loom);
            Object run = ((NamedDomainObjectCollection<?>) runs).findByName(CLIENT_RUN);
            if (run == null) {
                return task.getWorkingDir();
            }
            return project.file(run.getClass().getMethod("getRunDir").invoke(run));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new GradleException("Could not read the Loom client run directory", exception);
        }
    }

    private static void copy(Task task, File source, File runDirectory) {
        if (!source.isFile()) {
            task.getLogger().warn("{} points at {}, which does not exist; launching with the run directory's own options.",
                    PROPERTY, source);
            return;
        }
        Path target = runDirectory.toPath().resolve(OPTIONS_FILE);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not copy " + source + " to " + target, exception);
        }
        task.getLogger().lifecycle("Using Minecraft options from {}", source);
    }
}
