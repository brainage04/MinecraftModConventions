package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import io.github.brainage04.minecraftmodconventions.gradle.integration.IntegrationGameTestSuite;
import io.github.brainage04.minecraftmodconventions.gradle.integration.IntegrationGameTestsPlugin;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.ModPublishingExtension;
import io.github.brainage04.minecraftmodconventions.gradle.production.ClientGameTestProductionRunTask;
import io.github.brainage04.minecraftmodconventions.gradle.production.ProductionGameTestExtension;
import io.github.brainage04.minecraftmodconventions.gradle.production.ProductionGameTestsPlugin;
import io.github.brainage04.minecraftmodconventions.gradle.recorder.ClientGameTestRecorderExtension;
import io.github.brainage04.minecraftmodconventions.gradle.recorder.RecordClientGameTestTask;
import io.github.brainage04.minecraftmodconventions.gradle.workspace.WorkspaceDependenciesExtension;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configures the common, Fabric, and NeoForge modules used by the mod fleet. */
public final class MultiLoaderModConventionsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.multiloader-mod-conventions";

    private static final Pattern RESOURCE_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final String ARCHITECTURY_LOOM = "dev.architectury.loom";
    private static final String ARCHITECTURY_LOOM_NO_REMAP = "dev.architectury.loom-no-remap";
    private static final String RECORDER_PLUGIN = "io.github.brainage04.client-gametest-recorder";
    private static final String PRODUCTION_GAMETESTS_PLUGIN = "io.github.brainage04.production-gametests";
    private static final String WORKSPACE_DEPENDENCIES_PLUGIN = "io.github.brainage04.workspace-dependencies";
    private static final String MOD_PUBLISHING_PLUGIN = "io.github.brainage04.mod-publishing";
    private static final String JAVA_QUALITY_PLUGIN = "io.github.brainage04.java-quality-conventions";
    private static final String DEVAUTH_GROUP = "me.djtheredstoner";
    private static final String DEVAUTH_VERSION = "1.2.2";
    private static final String DEVAUTH_REPOSITORY =
            "https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1";
    /** Fabric API's development server GameTest run task; the NeoForge one is given the same name. */
    private static final String DEVELOPMENT_GAMETEST_TASK = "runGameTest";
    private static final String PRODUCTION_CLIENT_GAMETEST_TASK = "runProductionClientGameTest";
    private static final String PRODUCTION_SERVER_GAMETEST_TASK = "runProductionServerGameTest";
    private static final String PRODUCTION_GAMETEST_JAR_TASK = "productionGameTestJar";

    @Override
    public void apply(Project root) {
        if (root != root.getRootProject()) {
            throw new GradleException(PLUGIN_ID + " must be applied to the root project.");
        }

        Project common = requiredSubproject(root, "common");
        Project fabric = requiredSubproject(root, "fabric");
        Project neoForge = root.findProject(":neoforge");
        MultiLoaderModConventionsExtension extension = root.getExtensions().create(
                "multiLoaderModConventions",
                MultiLoaderModConventionsExtension.class,
                root.getObjects()
        );
        extension.getDevAuth().convention(hasClientSide(root));

        root.getPluginManager().apply("base");
        configureIdentity(root, common, fabric);
        if (neoForge != null) {
            configureIdentity(root, neoForge);
        }
        root.getPluginManager().apply(JAVA_QUALITY_PLUGIN);
        configureCommon(root, common);
        configureFabric(root, common, fabric, extension);
        if (neoForge != null) {
            configureNeoForge(root, common, fabric, neoForge, extension);
        }
        configureRootTasks(root, fabric, neoForge, extension);
    }

    private static void configureIdentity(Project root, Project... subprojects) {
        String group = requiredProperty(root, "maven_group");
        String version = requiredProperty(root, "mod_version");
        root.setGroup(group);
        root.setVersion(version);
        for (Project project : subprojects) {
            project.setGroup(group);
            project.setVersion(version);
            configureRepositories(project);
            configureJava(project, requiredIntegerProperty(root, "java_version"));
        }
    }

    /**
     * Declares only the repositories every mod needs. Owned libraries come from Maven Central (or a sibling
     * {@code build/local-repo} via workspace-dependencies); repositories only some mods need are declared by
     * those mods with {@code exclusiveContent} next to the dependency.
     */
    private static void configureRepositories(Project project) {
        RepositoryHandler repositories = project.getRepositories();
        repositories.mavenCentral();
        repositories.maven(repository -> {
            repository.setName("FabricMC");
            repository.setUrl("https://maven.fabricmc.net/");
            repository.content(content -> content.includeGroupAndSubgroups("net.fabricmc"));
        });
        repositories.maven(repository -> {
            repository.setName("NeoForged");
            repository.setUrl("https://maven.neoforged.net/releases/");
            repository.content(content -> content.includeGroupAndSubgroups("net.neoforged"));
        });
        repositories.maven(repository -> {
            repository.setName("Architectury");
            repository.setUrl("https://maven.architectury.dev/");
            repository.content(content -> content.includeGroupAndSubgroups("dev.architectury"));
        });
    }

    private static void configureJava(Project project, int release) {
        project.getPluginManager().apply("java");
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(release));
        java.setSourceCompatibility(JavaVersion.toVersion(release));
        java.setTargetCompatibility(JavaVersion.toVersion(release));
        java.withSourcesJar();
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getRelease().set(release);
        });
    }

    private static void configureCommon(Project root, Project common) {
        common.getPluginManager().apply(ARCHITECTURY_LOOM_NO_REMAP);
        common.getPluginManager().apply(ARCHITECTURY_LOOM);
        common.getPluginManager().apply(WORKSPACE_DEPENDENCIES_PLUGIN);
        common.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                .set(requiredProperty(root, "archives_base_name") + "-common");
        common.getDependencies().add("minecraft", minecraftDependency(root));
        common.getDependencies().add(
                "compileOnly",
                "net.fabricmc:fabric-loader:" + requiredProperty(root, "loader_version")
        );
        LoomGradleExtensionAPI loom = common.getExtensions().getByType(LoomGradleExtensionAPI.class);
        configureAccessWidener(root, loom);
        // Loom gives every project default client and server runs, but common has no loader
        // metadata, so its game is plain Minecraft without the mod. Removing the runs drops
        // their IDE run configurations; Loom's run tasks stay listed and are left as Loom makes them.
        loom.getRuns().clear();

        SourceSet commonMain = sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        File generatedResources = common.file("src/main/generated");
        if (generatedResources.isDirectory()) {
            commonMain.getResources().srcDir(generatedResources);
        }
        commonMain.getResources().exclude(".cache/**");
    }

    private static void configureFabric(
            Project root,
            Project common,
            Project fabric,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        fabric.getPluginManager().apply(ARCHITECTURY_LOOM_NO_REMAP);
        fabric.getPluginManager().apply(ARCHITECTURY_LOOM);
        LoomGradleExtensionAPI loom = fabric.getExtensions().getByType(LoomGradleExtensionAPI.class);
        if (property(root, "mod_side", "both").equalsIgnoreCase("both")) {
            loom.splitEnvironmentSourceSets();
        }
        fabric.getPluginManager().apply(RECORDER_PLUGIN);
        // The recorder drives :fabric:runProductionClientGameTest, so loom's development clientGameTest run
        // (and its :fabric:runClientGameTest task) must never be created.
        // The root runAllGameTests aggregates every Fabric and NeoForge GameTest run, so the production
        // plugin's :fabric:runAllProductionGameTests would only duplicate part of it.
        fabric.getExtensions().getExtraProperties()
                .set(ProductionGameTestsPlugin.DEVELOPMENT_CLIENT_GAMETEST_RUN_PROPERTY, false);
        fabric.getExtensions().getExtraProperties()
                .set(ProductionGameTestsPlugin.AGGREGATE_TASK_PROPERTY, false);
        fabric.getPluginManager().apply(PRODUCTION_GAMETESTS_PLUGIN);
        configureIntegrationGameTests(common, fabric, fleetExtension, false);
        fabric.getPluginManager().apply(WORKSPACE_DEPENDENCIES_PLUGIN);
        fabric.getPluginManager().apply(MOD_PUBLISHING_PLUGIN);
        fabric.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                .set(requiredProperty(root, "archives_base_name"));

        SourceSet commonMain = sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet fabricMain = sourceSets(fabric).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        loom.getMods().maybeCreate("main").sourceSet(fabricMain);
        loom.getMods().getByName("main").sourceSet(commonMain);
        SourceSet fabricClient = sourceSets(fabric).findByName("client");
        if (fabricClient != null) {
            loom.getMods().getByName("main").sourceSet(fabricClient);
        }
        configureDefaultRunDirectories(loom);
        // Fabric reuses its dedicated-server world; NeoForge's GameTest entrypoint already replaces its test world.
        fabric.getTasks().matching(task -> task.getName().equals(DEVELOPMENT_GAMETEST_TASK))
                .configureEach(task -> task.doFirst("resetGameTestWorld", _ ->
                        fabric.delete(fabric.file(loom.getRuns().getByName("gameTest").getRunDir())
                                .toPath().resolve("world"))));
        configureAccessWidener(root, loom);
        SourceSet fabricGameTest = sourceSets(fabric).findByName("gametest");
        if (fabricGameTest != null) {
            addSharedGameTests(fabric, common, fabricGameTest);
        }

        Configuration commonConfiguration = resolvableConfiguration(fabric, "common");
        extendIfPresent(fabric, "compileClasspath", commonConfiguration);
        extendIfPresent(fabric, "runtimeClasspath", commonConfiguration);
        extendIfPresent(fabric, "developmentFabric", commonConfiguration);

        ProjectDependency commonDependency = projectDependency(fabric, common);
        fabric.getDependencies().add("common", commonDependency);
        fabric.getDependencies().add("testImplementation", projectDependency(fabric, common));
        fabric.getDependencies().add("minecraft", minecraftDependency(root));
        fabric.getDependencies().add("implementation", "net.fabricmc:fabric-loader:" + requiredProperty(root, "loader_version"));
        fabric.getDependencies().add(
                "implementation",
                "net.fabricmc.fabric-api:fabric-api:" + requiredProperty(root, "fabric_api_version")
        );
        fabric.getDependencies().add(
                "testImplementation",
                "net.fabricmc:fabric-loader-junit:" + requiredProperty(root, "loader_version")
        );

        configureMergedOutputs(common, fabric);
        configureDevAuth(root, fabric, "DevAuth-fabric", fleetExtension);
        configureFabricTests(root, fabric);
        configureFabricGameTests(root, fabric, fleetExtension);
        configureWorkspaceDependency(root, fabric);
        configureFabricPublishing(root, fabric, fleetExtension);
        configureResourceExpansion(root, fabric, "src/main/resources/fabric.mod.json", "fabric.mod.json");
    }

    private static void addSharedGameTests(Project loader, Project common, SourceSet gameTest) {
        File sharedJava = common.file("src/gametest/java");
        if (sharedJava.isDirectory()) {
            gameTest.getJava().srcDir(sharedJava);
        }
        File sharedResources = common.file("src/gametest/resources");
        if (sharedResources.isDirectory()) {
            gameTest.getResources().srcDir(sharedResources);
        }
        TaskProvider<GenerateSharedGameTestResourcesTask> sharedStructures = loader.getTasks().register(
                "generateSharedGameTestResources",
                GenerateSharedGameTestResourcesTask.class,
                task -> task.getOutputDirectory().convention(
                        loader.getLayout().getBuildDirectory().dir("generated/minecraftmodconventions/gametest-resources")
                )
        );
        gameTest.getResources().srcDir(sharedStructures.flatMap(GenerateSharedGameTestResourcesTask::getOutputDirectory));
    }

    /**
     * Gives the loader every integration GameTest suite declared at the root: sources from {@code common/src/<name>}
     * (plus the loader's own {@code src/<name>}) and that loader's extra mods.
     */
    private static void configureIntegrationGameTests(
            Project common,
            Project loader,
            MultiLoaderModConventionsExtension fleetExtension,
            boolean neoForge
    ) {
        loader.getPluginManager().apply(IntegrationGameTestsPlugin.PLUGIN_ID);
        NamedDomainObjectContainer<IntegrationGameTestSuite> suites = integrationGameTestSuites(loader);
        fleetExtension.getIntegrationGameTests().all(shared -> suites.create(shared.getName(), suite -> {
            suite.getFilter().set(shared.getFilter());
            suite.getMods().set(neoForge ? shared.getNeoForgeMods() : shared.getFabricMods());
            suite.getGitMods().set(shared.getGitMods());
            suite.getSourceRoots().from(common.file("src/" + shared.getName()));
        }));
    }

    @SuppressWarnings("unchecked")
    private static NamedDomainObjectContainer<IntegrationGameTestSuite> integrationGameTestSuites(Project loader) {
        return (NamedDomainObjectContainer<IntegrationGameTestSuite>) loader.getExtensions()
                .getByName(IntegrationGameTestsPlugin.EXTENSION_NAME);
    }

    /**
     * Adds DevAuth to the development runtime only ({@code localRuntime} is neither packaged, published nor part
     * of the production runs). DevAuth stays inactive until the developer enables it.
     */
    private static void configureDevAuth(
            Project root,
            Project loader,
            String artifact,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        if (!hasClientSide(root)) {
            return;
        }
        loader.getRepositories().exclusiveContent(exclusive -> exclusive
                .forRepository(() -> loader.getRepositories().maven(repository -> {
                    repository.setName("DevAuth");
                    repository.setUrl(DEVAUTH_REPOSITORY);
                }))
                .filter(filter -> filter.includeGroup(DEVAUTH_GROUP)));
        String coordinate = DEVAUTH_GROUP + ":" + artifact + ":" + property(root, "devauth_version", DEVAUTH_VERSION);
        loader.getConfigurations().named("localRuntime").configure(configuration ->
                configuration.getDependencies().addAllLater(fleetExtension.getDevAuth().map(enabled -> enabled
                        ? List.of(loader.getDependencies().create(coordinate))
                        : List.of())));
    }

    private static boolean hasClientSide(Project root) {
        return !property(root, "mod_side", "both").equalsIgnoreCase("server");
    }

    private static void configureAccessWidener(Project root, LoomGradleExtensionAPI loom) {
        String modId = requiredProperty(root, "mod_id");
        File accessWidener = root.file("common/src/main/resources/" + modId + ".accesswidener");
        if (!accessWidener.isFile()) {
            accessWidener = root.file("fabric/src/main/resources/" + modId + ".accesswidener");
        }
        if (accessWidener.isFile()) {
            loom.getAccessWidenerPath().fileValue(accessWidener);
        }
    }

    private static void configureDefaultRunDirectories(LoomGradleExtensionAPI loom) {
        loom.getRuns().matching(run -> run.getName().equals("client"))
                .configureEach(run -> run.setRunDir("run/client"));
        loom.getRuns().matching(run -> run.getName().equals("server"))
                .configureEach(run -> run.setRunDir("run/server"));
    }

    private static void configureFabricTests(Project root, Project fabric) {
        String side = property(root, "mod_side", "both");
        fabric.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            task.systemProperty("fabric.side", side.equalsIgnoreCase("client") ? "client" : "server");
        });
    }

    private static void configureFabricGameTests(
            Project root,
            Project fabric,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        ClientGameTestRecorderExtension recorder = fabric.getExtensions()
                .getByType(ClientGameTestRecorderExtension.class);
        ProductionGameTestExtension production = fabric.getExtensions()
                .getByType(ProductionGameTestExtension.class);
        production.getIncludeClient().convention(fleetExtension.getFabricClientGameTests());
        production.getIncludeServer().convention(
                property(root, "mod_side", "both").equalsIgnoreCase("client")
                        ? fabric.getProviders().provider(() -> false)
                        : fleetExtension.getFabricServerGameTests()
        );
        production.getClientRunDir().set(recorder.getRunDir());
        Provider<Boolean> recorderManaged = fabric.getProviders().environmentVariable("GTR_RECORDING_MANAGED_XVFB")
                .map(Boolean::parseBoolean)
                .orElse(false);
        // The recorder provides its own display and routes game audio to an isolated sink.
        production.getClientUseXvfb().convention(recorderManaged.map(managed -> !managed));
        production.getClientSilenceAudio().convention(recorderManaged.map(managed -> !managed));

        fabric.getTasks().named("recordClientGameTest", RecordClientGameTestTask.class).configure(task -> {
            task.getRunTaskName().set("runProductionClientGameTest");
            task.onlyIf("Fabric client GameTests are enabled", spec -> fleetExtension.getFabricClientGameTests().get());
        });
        fabric.getTasks().named("prepareClientGameTestRun").configure(task ->
                task.mustRunAfter("prepareProductionGameTestRuns"));
        fabric.getTasks().withType(ClientGameTestProductionRunTask.class)
                .configureEach(task -> task.dependsOn("prepareClientGameTestRun"));
        // CI always calls :fabric:runProductionClientGameTest; with client GameTests off it has to exist and
        // do nothing. This runs after the production plugin's afterEvaluate, which registers the real task.
        fabric.afterEvaluate(_ -> {
            if (!fabric.getTasks().getNames().contains(PRODUCTION_CLIENT_GAMETEST_TASK)) {
                fabric.getTasks().register(PRODUCTION_CLIENT_GAMETEST_TASK, task -> {
                    task.setGroup("verification");
                    task.setDescription("Does nothing: Fabric client GameTests are off.");
                });
            }
        });
    }

    private static void configureWorkspaceDependency(Project root, Project fabric) {
        WorkspaceDependenciesExtension workspace = fabric.getExtensions()
                .getByType(WorkspaceDependenciesExtension.class);
        workspace.siblingMaven("MinecraftModConventions", declaration -> {
            declaration.getCoordinate().set(
                    "io.github.brainage04:minecraftmodconventions:"
                            + requiredProperty(root, "minecraftmodconventions_version")
            );
            declaration.getSiblingDirectory().set(root.getLayout().getProjectDirectory().dir("../MinecraftModConventions"));
        });
    }

    private static void configureFabricPublishing(
            Project root,
            Project fabric,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        ModPublishingExtension publishing = fabric.getExtensions().getByType(ModPublishingExtension.class);
        configureSharedPublishing(root, publishing);
        fabric.getTasks().matching(task -> task.getName().startsWith("publish"))
                .configureEach(task -> task.onlyIf(spec -> fleetExtension.getPublishing().get()));
    }

    private static void configureNeoForge(
            Project root,
            Project common,
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        neoForge.getPluginManager().apply(ARCHITECTURY_LOOM_NO_REMAP);
        neoForge.getPluginManager().apply(ARCHITECTURY_LOOM);
        neoForge.getPluginManager().apply(WORKSPACE_DEPENDENCIES_PLUGIN);
        configureIntegrationGameTests(common, neoForge, fleetExtension, true);
        neoForge.getPluginManager().apply(MOD_PUBLISHING_PLUGIN);
        neoForge.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                .set(requiredProperty(root, "archives_base_name") + "-neoforge");

        SourceSetContainer neoSourceSets = sourceSets(neoForge);
        SourceSet main = neoSourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet gameTest = neoSourceSets.maybeCreate("gametest");
        gameTest.setCompileClasspath(gameTest.getCompileClasspath()
                .plus(main.getOutput())
                .plus(neoForge.getConfigurations().getByName("compileClasspath")));
        gameTest.setRuntimeClasspath(gameTest.getRuntimeClasspath()
                .plus(gameTest.getOutput())
                .plus(gameTest.getCompileClasspath())
                .plus(neoForge.getConfigurations().getByName("runtimeClasspath")));
        addSharedGameTests(neoForge, common, gameTest);

        LoomGradleExtensionAPI loom = neoForge.getExtensions().getByType(LoomGradleExtensionAPI.class);
        File accessTransformer = neoForge.file("src/main/resources/META-INF/accesstransformer.cfg");
        loom.neoForge(extension -> {
            if (accessTransformer.isFile()) {
                extension.accessTransformer(accessTransformer);
            }
        });
        loom.getMods().maybeCreate("main").sourceSet(main);
        loom.getMods().getByName("main").sourceSet(sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME));
        loom.getMods().getByName("main").sourceSet(gameTest);
        // Named like Fabric API's dev server GameTest run, so both loaders have :<loader>:runGameTest.
        loom.getRuns().maybeCreate("gameTest").server();
        loom.getRuns().named("gameTest").configure(run -> {
            run.forgeTemplate("gameTestServer");
            run.name("NeoForge Game Tests");
            run.source(gameTest);
            run.runDir("run/gametest");
        });
        configureDefaultRunDirectories(loom);

        Configuration commonConfiguration = resolvableConfiguration(neoForge, "common");
        extendIfPresent(neoForge, "compileClasspath", commonConfiguration);
        neoForge.getDependencies().add("minecraft", minecraftDependency(root));
        neoForge.getDependencies().add("neoForge", "net.neoforged:neoforge:" + requiredProperty(root, "neoforge_version"));
        neoForge.getDependencies().add("common", projectDependency(neoForge, common));

        configureMergedOutputs(common, neoForge);
        configureDevAuth(root, neoForge, "DevAuth-neoforge", fleetExtension);
        configureNeoForgePublishing(root, fabric, neoForge, fleetExtension);
        configureResourceExpansion(
                root,
                neoForge,
                "src/main/resources/META-INF/neoforge.mods.toml",
                "META-INF/neoforge.mods.toml"
        );
        neoForge.getTasks().matching(task -> task.getName().equals(DEVELOPMENT_GAMETEST_TASK))
                .configureEach(task -> task.onlyIf(spec -> fleetExtension.getNeoForgeGameTests().get()));
        neoForge.afterEvaluate(_ -> integrationGameTestSuites(neoForge).forEach(suite -> neoForge.getTasks()
                .named(suite.getRunTaskName())
                .configure(task -> task.onlyIf(spec -> fleetExtension.getNeoForgeGameTests().get()))));
        configureNeoForgeProductionGameTests(root, neoForge, gameTest, fleetExtension);
    }

    /**
     * Registers {@code productionGameTestJar}, {@code installProductionServer} and
     * {@code runProductionServerGameTest}: the release JAR and the GameTest JAR on a NeoForge dedicated server
     * installed with the official installer, the NeoForge counterpart of Loom's Fabric production server run.
     * Each integration GameTest suite gets the same run with its extra mods as {@code runProduction<Name>GameTest}.
     */
    private static void configureNeoForgeProductionGameTests(
            Project root,
            Project neoForge,
            SourceSet gameTest,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        String modId = requiredProperty(root, "mod_id");
        String neoForgeVersion = requiredProperty(root, "neoforge_version");
        TaskProvider<GenerateProductionGameTestTickerTask> ticker = neoForge.getTasks().register(
                "generateProductionGameTestTicker",
                GenerateProductionGameTestTickerTask.class,
                task -> {
                    task.getModId().set(modId);
                    task.getOutputDirectory().convention(
                            neoForge.getLayout().getBuildDirectory().dir("generated/minecraftmodconventions/gametest-java")
                    );
                }
        );
        gameTest.getJava().srcDir(ticker.flatMap(GenerateProductionGameTestTickerTask::getOutputDirectory));

        TaskProvider<Jar> gameTestJar = neoForge.getTasks().register(PRODUCTION_GAMETEST_JAR_TASK, Jar.class, task -> {
            task.setGroup("build");
            task.setDescription("Packages the NeoForge GameTest source set for the production server run.");
            task.getArchiveClassifier().set("production-gametest");
            task.from(gameTest.getOutput());
        });

        Configuration installer = neoForge.getConfigurations().create("productionServerInstaller", configuration -> {
            configuration.setDescription("The official NeoForge installer used to install the production GameTest server.");
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);
        });
        neoForge.getDependencies().add(installer.getName(), "net.neoforged:neoforge:" + neoForgeVersion + ":installer");
        Provider<JavaLauncher> javaLauncher = neoForge.getExtensions().getByType(JavaToolchainService.class)
                .launcherFor(neoForge.getExtensions().getByType(JavaPluginExtension.class).getToolchain());
        Provider<Boolean> enabled = fleetExtension.getNeoForgeGameTests();

        TaskProvider<InstallNeoForgeServerTask> install = neoForge.getTasks().register(
                "installProductionServer",
                InstallNeoForgeServerTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Installs the NeoForge " + neoForgeVersion
                            + " dedicated server for runProductionServerGameTest.");
                    task.getInstaller().from(installer);
                    task.getNeoForgeVersion().set(neoForgeVersion);
                    task.getJavaLauncher().set(javaLauncher);
                    task.getServerDirectory().convention(neoForge.getLayout().getBuildDirectory()
                            .dir("minecraftmodconventions/neoforge-server/" + neoForgeVersion));
                    task.onlyIf("NeoForge GameTests are enabled", spec -> enabled.get());
                }
        );
        NeoForgeProductionRun run = new NeoForgeProductionRun(neoForge, modId, neoForgeVersion, install, gameTestJar,
                javaLauncher, enabled);
        run.register(PRODUCTION_SERVER_GAMETEST_TASK, "Runs the NeoForge server GameTests against the release JAR on a"
                + " production NeoForge server.", neoForge.files(), List.of());
        // The integration plugin creates each suite's JAR and mods configuration in an earlier afterEvaluate.
        neoForge.afterEvaluate(_ -> integrationGameTestSuites(neoForge).forEach(suite -> {
            String filter = IntegrationGameTestsPlugin.filter(suite);
            run.register(suite.getProductionRunTaskName(), "Runs the " + suite.getName() + " integration GameTests ("
                            + filter + ") with their extra mods against the release JAR on a production NeoForge server.",
                    IntegrationGameTestsPlugin.suiteMods(neoForge, suite), List.of("--tests", filter));
        }));
    }

    /** The settings every NeoForge production server GameTest run shares. */
    private record NeoForgeProductionRun(
            Project neoForge,
            String modId,
            String neoForgeVersion,
            TaskProvider<InstallNeoForgeServerTask> install,
            TaskProvider<Jar> gameTestJar,
            Provider<JavaLauncher> javaLauncher,
            Provider<Boolean> enabled
    ) {
        void register(String taskName, String description, FileCollection extraMods, List<String> programArgs) {
            neoForge.getTasks().register(taskName, NeoForgeServerGameTestProductionRunTask.class, task -> {
                task.setGroup("verification");
                task.setDescription(description);
                task.getServerDirectory().set(install.flatMap(InstallNeoForgeServerTask::getServerDirectory));
                task.getNeoForgeVersion().set(neoForgeVersion);
                task.getModId().set(modId);
                task.getModJar().set(neoForge.getTasks().named("jar", Jar.class).flatMap(Jar::getArchiveFile));
                task.getGameTestJar().set(gameTestJar.flatMap(Jar::getArchiveFile));
                task.getRuntimeMods().from(neoForge.getConfigurations().named("productionRuntimeMods"), extraMods);
                task.getProgramArgs().addAll(programArgs);
                task.getJavaLauncher().set(javaLauncher);
                String runName = Character.toLowerCase(taskName.charAt("run".length())) + taskName.substring("run".length() + 1);
                task.getRunDir().convention(neoForge.getLayout().getBuildDirectory().dir("run/" + runName));
                task.getReportFile().convention(neoForge.getLayout().getBuildDirectory()
                        .file("test-results/" + taskName + "/TEST-gametest.xml"));
                task.onlyIf("NeoForge GameTests are enabled", spec -> enabled.get());
            });
        }
    }

    private static void configureMergedOutputs(Project common, Project loader) {
        SourceSet commonMain = sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        loader.getTasks().named("jar", Jar.class).configure(task -> {
            task.from(commonMain.getOutput());
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        });
        loader.getTasks().named("sourcesJar", Jar.class).configure(task -> {
            task.from(commonMain.getAllSource());
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        });
        loader.getTasks().matching(task -> task.getName().startsWith("run"))
                .configureEach(task -> task.dependsOn(common.getTasks().named("jar")));
    }

    private static void configureNeoForgePublishing(
            Project root,
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        String version = requiredProperty(root, "mod_version");
        ModPublishingExtension publishing = neoForge.getExtensions().getByType(ModPublishingExtension.class);
        configureSharedPublishing(root, publishing);
        publishing.getVersion().set(version + "-neoforge");
        publishing.getReleaseTag().set("v" + version);
        publishing.getDisplayName().set(requiredProperty(root, "mod_name") + " " + version + " (NeoForge)");
        publishing.getModLoaders().set(List.of("neoforge"));
        publishing.getFabricModJson().set(fabric.getLayout().getBuildDirectory().file("resources/main/fabric.mod.json"));
        neoForge.getTasks().named("validateModPublication")
                .configure(task -> task.dependsOn(fabric.getTasks().named("processResources")));
        neoForge.getTasks().matching(task -> task.getName().startsWith("publish"))
                .configureEach(task -> task.onlyIf(spec -> fleetExtension.getPublishing().get()));

        neoForge.getTasks().named("jar", Jar.class).configure(task -> task.from(
                root.file("LICENSE"),
                copy -> copy.rename(fileName -> fileName + "_" + requiredProperty(root, "archives_base_name") + "-neoforge")
        ));
    }

    private static void configureSharedPublishing(Project root, ModPublishingExtension publishing) {
        publishing.getGithub().getRepository().convention("brainage04/" + root.getName());
        String modId = requiredProperty(root, "mod_id");
        String side = property(root, "mod_side", "both").toLowerCase();
        publishing.getCurseforge().getClient().set(!side.equals("server"));
        publishing.getCurseforge().getServer().set(!side.equals("client"));

        File icon = root.file("common/src/main/resources/assets/" + modId + "/icon.png");
        if (!icon.isFile()) {
            icon = root.file("fabric/src/main/resources/assets/" + modId + "/icon.png");
        }
        if (icon.isFile()) {
            publishing.getModrinth().getIconFile().convention(
                    root.getLayout().getProjectDirectory().file(root.relativePath(icon))
            );
        }
        File neoForgeDependencies = root.file(".modrinth/neoforge-dependencies.json");
        if (neoForgeDependencies.isFile()) {
            publishing.getSourceFabricModJson().set(
                    root.getLayout().getProjectDirectory().file(".modrinth/neoforge-dependencies.json")
            );
        }
    }

    private static void configureResourceExpansion(
            Project root,
            Project project,
            String sourcePath,
            String targetPattern
    ) {
        File resource = project.file(sourcePath);
        if (!resource.isFile()) {
            return;
        }
        Map<String, String> expansion = resourceExpansion(root, project, resource);
        project.getTasks().withType(ProcessResources.class).configureEach(task -> {
            task.getInputs().properties(expansion);
            task.filesMatching(targetPattern, details -> details.expand(expansion));
        });
    }

    private static Map<String, String> resourceExpansion(Project root, Project project, File resource) {
        String content;
        try {
            content = Files.readString(resource.toPath());
        } catch (IOException exception) {
            throw new GradleException("Cannot read resource metadata " + resource + ".", exception);
        }
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = RESOURCE_PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = switch (name) {
                case "version", "mod_version" -> project.getVersion();
                case MinecraftVersionRange.PROPERTY -> MinecraftVersionRange.of(requiredProperty(root, "minecraft_version"));
                default -> root.findProperty(name);
            };
            if (value == null || value.toString().isBlank()) {
                throw new GradleException(resource + " requires project property '" + name + "'.");
            }
            values.put(name, value.toString());
        }
        return Map.copyOf(values);
    }

    private static void configureRootTasks(
            Project root,
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        // Only tasks that span both loaders live at the root; single-loader tasks are run as
        // :fabric:<task> or :neoforge:<task>.
        TaskProvider<Task> runAllGameTests = root.getTasks().register("runAllGameTests", task -> {
            task.setGroup("verification");
            task.setDescription("Runs every configured loader's development and production GameTests,"
                    + " including integration suites and the optional Fabric production client suite.");
        });
        // The Fabric production runs are registered in :fabric's afterEvaluate and the extension is final only
        // once every build script ran, so the runs are collected when all projects are evaluated.
        root.getGradle().projectsEvaluated(_ -> {
            List<TaskProvider<? extends Task>> runs = gameTestRuns(fabric, neoForge, fleetExtension);
            runAllGameTests.configure(task -> task.dependsOn(runs));
            // Every run starts a Minecraft server or client; with --parallel they would otherwise launch side by
            // side. Running them one at a time, development runs first, keeps a failing source-level test from
            // waiting behind the production installs and runs.
            for (int index = 1; index < runs.size(); index++) {
                List<TaskProvider<? extends Task>> earlier = runs.subList(0, index);
                runs.get(index).configure(task -> task.mustRunAfter(earlier));
            }
        });
        configureReleaseArtifacts(root, fabric, neoForge);
    }

    /**
     * The GameTest runs of {@code runAllGameTests}, in run order: development runs, each integration GameTest suite's
     * development runs, the ordinary production runs, then each suite's production runs. {@code :fabric:runGameTest}
     * exists only when Fabric API's server GameTests are on (not for {@code mod_side=client}), and is left out with
     * {@code fabricServerGameTests = false} like the production server run and every Fabric suite run. Every
     * NeoForge run is always included: with {@code neoForgeGameTests = false} they are skipped.
     */
    private static List<TaskProvider<? extends Task>> gameTestRuns(
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        boolean fabricServer = fleetExtension.getFabricServerGameTests().get()
                && fabric.getTasks().getNames().contains(DEVELOPMENT_GAMETEST_TASK);
        List<IntegrationGameTestSuite> fabricSuites = fabricServer ? List.copyOf(integrationGameTestSuites(fabric)) : List.of();
        List<IntegrationGameTestSuite> neoForgeSuites =
                neoForge == null ? List.of() : List.copyOf(integrationGameTestSuites(neoForge));
        List<String> fabricRuns = new ArrayList<>();
        List<TaskProvider<? extends Task>> runs = new ArrayList<>();
        if (fabricServer) {
            runs.add(fabric.getTasks().named(DEVELOPMENT_GAMETEST_TASK));
        }
        if (neoForge != null) {
            runs.add(neoForge.getTasks().named(DEVELOPMENT_GAMETEST_TASK));
        }
        fabricSuites.forEach(suite -> runs.add(fabric.getTasks().named(suite.getRunTaskName())));
        neoForgeSuites.forEach(suite -> runs.add(neoForge.getTasks().named(suite.getRunTaskName())));
        if (fabric.getTasks().getNames().contains(PRODUCTION_SERVER_GAMETEST_TASK)) {
            runs.add(fabric.getTasks().named(PRODUCTION_SERVER_GAMETEST_TASK));
            fabricSuites.forEach(suite -> fabricRuns.add(suite.getProductionRunTaskName()));
        }
        fabric.getTasks().withType(ClientGameTestProductionRunTask.class).getNames()
                .forEach(name -> runs.add(fabric.getTasks().named(name)));
        if (neoForge != null) {
            runs.add(neoForge.getTasks().named(PRODUCTION_SERVER_GAMETEST_TASK));
        }
        fabricRuns.forEach(name -> runs.add(fabric.getTasks().named(name)));
        neoForgeSuites.forEach(suite -> runs.add(neoForge.getTasks().named(suite.getProductionRunTaskName())));
        return runs;
    }

    private static void configureReleaseArtifacts(Project root, Project fabric, Project neoForge) {
        TaskProvider<Jar> fabricJar = fabric.getTasks().named("jar", Jar.class);
        TaskProvider<Jar> neoForgeJar = neoForge == null ? null : neoForge.getTasks().named("jar", Jar.class);
        TaskProvider<Sync> collect = root.getTasks().register("collectReleaseArtifacts", Sync.class, task -> {
            task.setGroup("build");
            task.dependsOn(fabricJar);
            task.from(fabricJar.flatMap(Jar::getArchiveFile));
            if (neoForgeJar != null) {
                task.from(neoForgeJar.flatMap(Jar::getArchiveFile));
            }
            task.into(root.getLayout().getBuildDirectory().dir("libs"));
        });
        root.getTasks().named("build").configure(task -> {
            task.setDescription("Builds and tests each configured loader, then collects its production JAR.");
            root.getSubprojects().forEach(project -> task.dependsOn(project.getTasks().named("build")));
            task.dependsOn(collect);
        });
    }

    private static SourceSetContainer sourceSets(Project project) {
        return project.getExtensions().getByType(SourceSetContainer.class);
    }

    private static Configuration resolvableConfiguration(Project project, String name) {
        Configuration configuration = project.getConfigurations().maybeCreate(name);
        configuration.setCanBeResolved(true);
        configuration.setCanBeConsumed(false);
        return configuration;
    }

    private static void extendIfPresent(Project project, String name, Configuration parent) {
        Configuration configuration = project.getConfigurations().findByName(name);
        if (configuration != null) {
            configuration.extendsFrom(parent);
        }
    }

    private static ProjectDependency projectDependency(Project owner, Project target) {

        ProjectDependency dependency = (ProjectDependency) owner.getDependencies()
                .project(Map.of("path", target.getPath()));
        dependency.setTransitive(false);
        return dependency;
    }

    private static String minecraftDependency(Project root) {
        return "com.mojang:minecraft:" + requiredProperty(root, "minecraft_version");
    }

    private static Project requiredSubproject(Project root, String name) {
        Project project = root.findProject(":" + name);
        if (project == null) {
            throw new GradleException(PLUGIN_ID + " requires subproject ':" + name + "'.");
        }
        return project;
    }

    private static int requiredIntegerProperty(Project project, String name) {
        String value = requiredProperty(project, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new GradleException("Project property '" + name + "' must be an integer, but was '" + value + "'.", exception);
        }
    }

    private static String requiredProperty(Project project, String name) {
        Object value = project.findProperty(name);
        if (value == null || value.toString().isBlank()) {
            throw new GradleException(PLUGIN_ID + " requires project property '" + name + "'.");
        }
        return value.toString().strip();
    }

    private static String property(Project project, String name, String fallback) {
        Object value = project.findProperty(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString().strip();
    }
}
