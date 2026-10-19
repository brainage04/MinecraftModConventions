package io.github.brainage04.minecraftmodconventions.gradle.production;

import net.fabricmc.loom.api.fabricapi.FabricApiExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import java.util.ArrayList;
import java.util.List;

/** Configures opt-in Fabric Loom production GameTest tasks. */
public final class ProductionGameTestsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.production-gametests";
    private static final String RUNTIME_LIBRARIES_CONFIGURATION = "productionGameTestRuntimeLibraries";
    private static final String CLIENT_GAMETEST_ENABLED_PROPERTY = "minecraftmodconventions.clientGameTest";
    /**
     * Set to {@code false} (as an extra property, before applying this plugin) to skip Loom's development
     * {@code clientGameTest} run and its {@code runClientGameTest} task while keeping the production client run.
     */
    public static final String DEVELOPMENT_CLIENT_GAMETEST_RUN_PROPERTY =
            "minecraftmodconventions.developmentClientGameTestRun";
    /**
     * Set to {@code false} (as an extra property, before applying this plugin) to skip the
     * {@code runAllProductionGameTests} aggregate, for builds that aggregate the production runs elsewhere.
     */
    public static final String AGGREGATE_TASK_PROPERTY = "minecraftmodconventions.productionGameTestsAggregate";

    @Override
    public void apply(Project project) {
        requireFabricApi(project);
        ModSide modSide = ModSide.from(project);
        configureDevelopmentGameTests(project, modSide);
        ProductionGameTestExtension extension = project.getExtensions().create(
                "productionGameTests",
                ProductionGameTestExtension.class,
                project.getObjects(),
                project.getLayout()
        );
        extension.getIncludeClient().convention(modSide != ModSide.SERVER);
        extension.getIncludeServer().convention(modSide != ModSide.CLIENT);
        project.afterEvaluate(_ -> configureProductionGameTests(project, extension));
    }

    private static void configureDevelopmentGameTests(Project project, ModSide modSide) {
        FabricApiExtension fabricApi = project.getExtensions().getByType(FabricApiExtension.class);
        Object modId = project.findProperty("mod_id");
        if (modId == null || modId.toString().isBlank()) {
            throw new GradleException(PLUGIN_ID + " requires project property 'mod_id'.");
        }
        boolean developmentClientRun = !isDisabled(project, DEVELOPMENT_CLIENT_GAMETEST_RUN_PROPERTY);
        fabricApi.configureTests(settings -> {
            settings.getCreateSourceSet().set(true);
            settings.getModId().set(modId.toString().strip() + "-gametest");
            settings.getEnableGameTests().set(modSide != ModSide.CLIENT);
            settings.getEnableClientGameTests().set(modSide != ModSide.SERVER && developmentClientRun);
            settings.getEula().set(modSide != ModSide.SERVER && developmentClientRun);
        });
    }

    private static void configureProductionGameTests(Project project, ProductionGameTestExtension extension) {
        if (!extension.getIncludeClient().get() && !extension.getIncludeServer().get()) {
            return;
        }

        addProductionFabricApiDependency(project, extension);
        Configuration runtimeLibraries = createProductionRuntimeLibraries(project, extension);
        TaskProvider<Jar> gameTestJar = registerProductionGameTestJar(project);
        TaskProvider<PrepareProductionGameTestRunsTask> prepareRuns =
                registerPrepareProductionGameTestRuns(project, extension);
        List<TaskProvider<? extends Task>> productionTasks = new ArrayList<>();
        if (extension.getIncludeServer().get()) {
            productionTasks.add(registerServerProductionGameTest(
                    project,
                    extension,
                    gameTestJar,
                    prepareRuns,
                    runtimeLibraries
            ));
        }
        if (extension.getIncludeClient().get()) {
            productionTasks.add(registerClientProductionGameTest(
                    project,
                    extension,
                    gameTestJar,
                    prepareRuns,
                    runtimeLibraries
            ));
        }

        if (isDisabled(project, AGGREGATE_TASK_PROPERTY)) {
            return;
        }
        project.getTasks().register("runAllProductionGameTests", task -> {
            task.setGroup("verification");
            task.setDescription("Runs every configured production GameTest task.");
            productionTasks.forEach(task::dependsOn);
        });
    }

    private static TaskProvider<? extends Task> registerClientProductionGameTest(
            Project project,
            ProductionGameTestExtension productionExtension,
            TaskProvider<Jar> gameTestJar,
            TaskProvider<PrepareProductionGameTestRunsTask> prepareRuns,
            Configuration runtimeLibraries
    ) {
        return project.getTasks().register(
                "runProductionClientGameTest",
                ClientGameTestProductionRunTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Runs Fabric client GameTests in Loom's production client environment.");
                    task.getRunDir().convention(productionExtension.getClientRunDir());
                    task.dependsOn(gameTestJar);
                    task.dependsOn(prepareRuns);
                    task.getMods().from(gameTestJar.flatMap(Jar::getArchiveFile));
                    task.getRuntimeLibraries().from(runtimeLibraries);
                    task.includeRuntimeLibrariesInClasspath();
                    task.getUseXVFB().convention(productionExtension.getClientUseXvfb());
                    task.getSilenceAudio().convention(productionExtension.getClientSilenceAudio());
                    task.getJvmArgs().add("-Dfabric.client.gametest");
                    if (productionExtension.getDisableClientNetworkSynchronizer().get()) {
                        task.getJvmArgs().add("-Dfabric.client.gametest.disableNetworkSynchronizer=true");
                    }
                    task.getJvmArgs().add(
                            "-D" + CLIENT_GAMETEST_ENABLED_PROPERTY + "=true"
                    );
                    task.getJvmArgs().addAll(productionExtension.getClientJvmArgs());
                    task.getProgramArgs().addAll(productionExtension.getClientProgramArgs());
                }
        );
    }

    private static TaskProvider<? extends Task> registerServerProductionGameTest(
            Project project,
            ProductionGameTestExtension extension,
            TaskProvider<Jar> gameTestJar,
            TaskProvider<PrepareProductionGameTestRunsTask> prepareRuns,
            Configuration runtimeLibraries
    ) {
        return project.getTasks().register(
                "runProductionServerGameTest",
                ServerGameTestProductionRunTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Runs Fabric server GameTests in Loom's production server environment.");
                    task.getRunDir().convention(extension.getServerRunDir());
                    // Register after construction so this precedes Loom's inherited @TaskAction methods.
                    task.doFirst("resetGameTestWorld", _ -> task.resetWorld());
                    task.dependsOn(gameTestJar);
                    task.dependsOn(prepareRuns);
                    task.getMods().from(gameTestJar.flatMap(Jar::getArchiveFile));
                    task.getRuntimeLibraries().from(runtimeLibraries);
                    task.includeRuntimeLibrariesInClasspath();
                    task.getJvmArgs().add("-Dfabric-api.gametest");
                    task.getJvmArgs().addAll(extension.getServerJvmArgs());
                    task.getProgramArgs().addAll(extension.getServerProgramArgs());
                    if (extension.getServerInstallerVersion().isPresent()) {
                        task.getInstallerVersion().set(extension.getServerInstallerVersion().get());
                    }
                }
        );
    }

    private static TaskProvider<Jar> registerProductionGameTestJar(Project project) {
        SourceSet gameTestSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .findByName("gametest");
        if (gameTestSourceSet == null) {
            throw new GradleException(
                    "productionGameTests requires the 'gametest' source set. "
                            + "Configure Fabric API GameTests before the project is evaluated."
            );
        }
        return project.getTasks().register("productionGameTestJar", Jar.class, task -> {
            task.setGroup("build");
            task.setDescription("Packages the consumer GameTest source set for production runs.");
            task.getArchiveClassifier().set("production-gametest");
            task.from(gameTestSourceSet.getOutput());
        });
    }

    private static TaskProvider<PrepareProductionGameTestRunsTask> registerPrepareProductionGameTestRuns(
            Project project,
            ProductionGameTestExtension extension
    ) {
        return project.getTasks().register(
                "prepareProductionGameTestRuns",
                PrepareProductionGameTestRunsTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Writes the production client's options and accepts embedded server EULAs.");
                    task.getClientEulaFile().convention(extension.getClientRunDir().file("eula.txt"));
                    task.getClientOptionsFile().convention(extension.getClientRunDir().file("options.txt"));
                    task.getClientOptionsProfile().convention(ClientGameTestOptions.profile(
                            project.getProviders(), ClientGameTestOptions.RECORDER_MANAGED_ENV));
                    task.getClientSoundDevice().convention(project.getProviders()
                            .environmentVariable(ClientGameTestOptions.RECORDER_AUDIO_DEVICE_ENV)
                            .orElse(""));
                    task.getDisableSocialInteractionsToast().convention(true);
                    task.getServerEulaFile().convention(extension.getServerRunDir().file("eula.txt"));
                }
        );
    }

    private static Configuration createProductionRuntimeLibraries(
            Project project,
            ProductionGameTestExtension extension
    ) {
        Configuration runtimeLibraries = project.getConfigurations().maybeCreate(RUNTIME_LIBRARIES_CONFIGURATION);
        runtimeLibraries.setDescription("Non-mod libraries required by production GameTest runs.");
        runtimeLibraries.setCanBeConsumed(false);
        runtimeLibraries.setCanBeResolved(true);
        extension.getRuntimeLibraryDependencies().get().stream()
                .filter(dependency -> dependency != null && !dependency.isBlank())
                .map(String::strip)
                .forEach(dependency -> project.getDependencies().add(runtimeLibraries.getName(), dependency));
        return runtimeLibraries;
    }

    private static void addProductionFabricApiDependency(Project project, ProductionGameTestExtension extension) {
        if (project.getConfigurations().findByName("productionRuntimeMods") == null) {
            throw new GradleException(
                    "productionGameTests requires a Fabric-compatible Loom plugin's productionRuntimeMods configuration."
            );
        }
        if (extension.getIncludeFabricApiDependency().get()) {
            String propertyName = extension.getFabricApiVersionProperty().get();
            Object version = project.findProperty(propertyName);
            if (version == null || version.toString().isBlank()) {
                throw new GradleException("productionGameTests requires project property '" + propertyName
                        + "' or includeFabricApiDependency=false.");
            }
            project.getDependencies().add("productionRuntimeMods", "net.fabricmc.fabric-api:fabric-api:" + version);
        }
        extension.getRuntimeModDependencies().get().stream()
                .filter(dependency -> dependency != null && !dependency.isBlank())
                .map(String::strip)
                .forEach(dependency -> project.getDependencies().add("productionRuntimeMods", dependency));
    }

    private static boolean isDisabled(Project project, String property) {
        return "false".equalsIgnoreCase(String.valueOf(project.findProperty(property)).strip());
    }

    private static void requireFabricApi(Project project) {
        if (project.getExtensions().findByType(FabricApiExtension.class) == null) {
            throw new GradleException(
                    PLUGIN_ID + " requires a Fabric-compatible Loom plugin with Fabric API support to be applied first."
            );
        }
    }

    private enum ModSide {
        CLIENT,
        SERVER,
        BOTH;

        private static ModSide from(Project project) {
            Object configured = project.findProperty("mod_side");
            if (configured == null || configured.toString().isBlank()) {
                return BOTH;
            }
            return switch (configured.toString().strip().toLowerCase()) {
                case "client" -> CLIENT;
                case "server" -> SERVER;
                case "both" -> BOTH;
                default -> throw new GradleException(
                        PLUGIN_ID + " requires 'mod_side' to be client, server, or both."
                );
            };
        }
    }
}
