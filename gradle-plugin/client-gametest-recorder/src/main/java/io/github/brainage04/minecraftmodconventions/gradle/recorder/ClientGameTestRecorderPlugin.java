package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import io.github.brainage04.minecraftmodconventions.gradle.production.ClientGameTestOptions;
import io.github.brainage04.minecraftmodconventions.gradle.production.ClientGameTestProductionRunTask;
import io.github.brainage04.minecraftmodconventions.gradle.production.PrepareProductionGameTestRunsTask;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.task.AbstractRunTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Configures recording support for Fabric client GameTests. */
public final class ClientGameTestRecorderPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.client-gametest-recorder";
    private static final String PRODUCTION_GAMETESTS_PLUGIN_ID = "io.github.brainage04.production-gametests";
    private static final String CLIENT_GAMETEST_ENABLED_PROPERTY = "minecraftmodconventions.clientGameTest";
    private static final String RUNTIME_HELPER_GROUP = "io.github.brainage04";
    private static final String RUNTIME_HELPER_NAME = "minecraftmodconventions";
    private static final String RUNTIME_PROPERTY_PREFIX = "minecraftmodconventions.clientGameTestRecorder.";

    @Override
    public void apply(Project project) {
        requireLoom(project);
        ClientGameTestRecorderExtension extension = project.getExtensions().create(
                "clientGameTestRecorder",
                ClientGameTestRecorderExtension.class,
                project.getObjects(),
                project.getLayout(),
                project.getProviders()
        );
        project.getPluginManager().withPlugin(
                PRODUCTION_GAMETESTS_PLUGIN_ID,
                _ -> configureProductionGameTests(project, extension)
        );
        String recordingRunDirectory = project.getProviders()
                .environmentVariable("GTR_RECORDING_RUN_DIR")
                .getOrNull();
        if (recordingRunDirectory != null && !recordingRunDirectory.isBlank()) {
            extension.getRunDir().fileValue(project.file(recordingRunDirectory));
        }

        TaskProvider<PrepareClientGameTestRunTask> prepareTask = project.getTasks().register(
                "prepareClientGameTestRun",
                PrepareClientGameTestRunTask.class,
                task -> configurePrepareTask(project, extension, task)
        );
        prepareTask.configure(task -> task.mustRunAfter(project.getTasks().matching(candidate -> candidate.getName().equals("deleteGameTestRunDir"))));

        project.getTasks().register("recordClientGameTest", RecordClientGameTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the Fabric client GameTest task under a recorded Xvfb/PipeWire/ffmpeg session.");
            task.getProjectDirectory().convention(project.getRootProject().getLayout().getProjectDirectory());
            task.getRunDirectory().convention(extension.getRunDir());
            task.getRecordingAudioDeviceProjectProperty().convention(extension.getRecordingAudioDeviceProperty());
            task.getRunTaskName().convention(clientGameTestTaskPath(project.getPath()));
            task.getOutputs().upToDateWhen(_ -> false);
        });

        // Loom's development runClientGameTest only exists when the production-gametests plugin keeps the
        // development client run (the multi-loader conventions drop it and record the production client run).
        project.afterEvaluate(_ -> project.getTasks()
                .matching(task -> task.getName().equals("runClientGameTest"))
                .configureEach(task -> configureRunClientGameTest(project, extension, prepareTask, task)));
    }

    /**
     * True when the recorder runs loom's own {@code runClientGameTest} task rather than another run task such as
     * {@code runProductionClientGameTest}; only then is that run pointed at the recorder directory.
     */
    static boolean drivesLoomClientGameTestRun(Project project) {
        String configuredRun = project.getTasks()
                .named("recordClientGameTest", RecordClientGameTestTask.class)
                .map(task -> task.getRunTaskName().getOrElse(""))
                .getOrElse("");
        return configuredRun.endsWith("runClientGameTest");
    }

    static String clientGameTestTaskPath(String projectPath) {
        return ":".equals(projectPath) ? "runClientGameTest" : projectPath + ":runClientGameTest";
    }

    static String clientGameTestRunDirectory(Project project, File runDirectory) {
        return project.relativePath(runDirectory);
    }

    /**
     * With production-gametests, the runtime helper joins both client runs, so the production client gets the same
     * options as the development one and both launches get a startup deadline.
     */
    private static void configureProductionGameTests(Project project, ClientGameTestRecorderExtension extension) {
        addRuntimeHelperDependencies(project);
        project.getTasks().withType(PrepareProductionGameTestRunsTask.class).configureEach(task -> {
            task.getClientSoundDevice().set(recordingAudioDevice(project, extension));
            task.getDisableSocialInteractionsToast().set(extension.getDisableSocialInteractionsToast());
        });
        project.getTasks().withType(ClientGameTestProductionRunTask.class).configureEach(task ->
                ClientStartupDeadline.install(
                        task,
                        readyFile(project, task),
                        extension.getClientStartupTimeoutSeconds(),
                        task.getJvmArgs()::add
                ));
    }

    private static Path readyFile(Project project, Task task) {
        return project.getLayout().getBuildDirectory()
                .file("minecraftmodconventions/client-ready/" + task.getName())
                .get().getAsFile().toPath();
    }

    private static Provider<String> recordingAudioDevice(Project project, ClientGameTestRecorderExtension extension) {
        ProviderFactory providers = project.getProviders();
        return extension.getRecordingAudioDeviceProperty()
                .flatMap(providers::gradleProperty)
                .orElse(extension.getRecordingAudioDeviceEnv().flatMap(providers::environmentVariable))
                .orElse("");
    }

    private static void addRuntimeHelperDependencies(Project project) {
        Object version = project.findProperty("minecraftmodconventions_version");
        if (version == null || version.toString().isBlank()) {
            throw new GradleException(
                    PLUGIN_ID + " requires project property 'minecraftmodconventions_version' "
                            + "when " + PRODUCTION_GAMETESTS_PLUGIN_ID + " is applied."
            );
        }
        String dependency = RUNTIME_HELPER_GROUP + ":" + RUNTIME_HELPER_NAME + ":" + version.toString().strip();
        project.getDependencies().add("gametestImplementation", dependency);
        project.getDependencies().add("productionRuntimeMods", dependency);
    }

    private static void configurePrepareTask(Project project, ClientGameTestRecorderExtension extension, PrepareClientGameTestRunTask task) {
        task.getRecordingAudioDevice().convention(recordingAudioDevice(project, extension));
        task.getProfile().convention(ClientGameTestOptions.profile(task.getRecordingAudioDevice()));
        task.getDisableSocialInteractionsToast().convention(extension.getDisableSocialInteractionsToast());
        task.getOptionsFile().convention(extension.getRunDir().file("options.txt"));
    }

    private static void configureRunClientGameTest(
            Project project,
            ClientGameTestRecorderExtension extension,
            TaskProvider<PrepareClientGameTestRunTask> prepareTask,
            Task task
    ) {
        task.dependsOn(prepareTask);
        LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
        if (drivesLoomClientGameTestRun(project)) {
            String runDirectory = clientGameTestRunDirectory(project, extension.getRunDir().get().getAsFile());
            loom.getRuns().named("clientGameTest").configure(run -> run.setRunDir(runDirectory));
        }
        AbstractRunTask loomRunTask = requireLoomRunTask(task);
        JavaExec javaExec = requireJavaExec(task);
        javaExec.jvmArgs(List.of(
                "-Dfabric.client.gametest.disableNetworkSynchronizer=true",
                "-D" + CLIENT_GAMETEST_ENABLED_PROPERTY + "=true",
                runtimeProperty("disableUnsecureChatToast", extension.getDisableUnsecureChatToast().get()),
                runtimeProperty("disableRecipeToasts", extension.getDisableRecipeToasts().get()),
                runtimeProperty("disableAdvancementToasts", extension.getDisableAdvancementToasts().get()),
                runtimeProperty("disableAdvancementChatMessages", extension.getDisableAdvancementChatMessages().get())
        ));

        String openAlDrivers = project.getProviders()
                .environmentVariable("ALSOFT_DRIVERS")
                .filter(value -> !value.isBlank())
                .getOrElse("null");
        javaExec.environment("ALSOFT_DRIVERS", openAlDrivers);
        for (String name : List.of("ALSOFT_CONF", "ALSOFT_PULSE_DEFAULT", "PULSE_SINK", "PULSE_SERVER", "PULSE_PROP")) {
            String value = project.getProviders().environmentVariable(name).getOrNull();
            if (value != null && !value.isBlank()) {
                javaExec.environment(name, value);
            }
        }

        String managedXvfbValue = project.getProviders().environmentVariable(extension.getManagedXvfbEnv().get()).getOrNull();
        if (truthy(managedXvfbValue)) {
            loomRunTask.getUseXvfb().set(false);
        }
        if (project.getPluginManager().hasPlugin(PRODUCTION_GAMETESTS_PLUGIN_ID)) {
            ClientStartupDeadline.install(
                    task,
                    readyFile(project, task),
                    extension.getClientStartupTimeoutSeconds(),
                    javaExec::jvmArgs
            );
        }
    }

    private static void requireLoom(Project project) {
        if (project.getExtensions().findByType(LoomGradleExtensionAPI.class) == null) {
            throw new GradleException(
                    PLUGIN_ID + " requires a Fabric-compatible Loom plugin to be applied first."
            );
        }
    }

    private static String runtimeProperty(String name, boolean value) {
        return "-D" + RUNTIME_PROPERTY_PREFIX + name + "=" + value;
    }

    private static JavaExec requireJavaExec(Task task) {
        if (task instanceof JavaExec javaExec) {
            return javaExec;
        }
        throw new GradleException("Task " + task.getPath() + " must be a JavaExec task.");
    }

    private static AbstractRunTask requireLoomRunTask(Task task) {
        if (task instanceof AbstractRunTask loomRunTask) {
            return loomRunTask;
        }
        throw new GradleException("Task " + task.getPath() + " must be a Fabric Loom run task to disable Loom-managed Xvfb.");
    }

    private static boolean truthy(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return List.of("1", "true", "yes", "on").contains(normalized);
    }
}
