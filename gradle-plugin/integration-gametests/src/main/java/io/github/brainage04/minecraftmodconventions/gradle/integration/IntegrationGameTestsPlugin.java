package io.github.brainage04.minecraftmodconventions.gradle.integration;

import io.github.brainage04.minecraftmodconventions.gradle.production.ProductionGameTestsPlugin;
import io.github.brainage04.minecraftmodconventions.gradle.production.ServerGameTestProductionRunTask;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Registers integration GameTest suites: GameTests that need extra mods (published Maven artifacts or pinned
 * commits of other mod repositories) loaded beside the mod under test. Each suite has its own source set, packaged
 * as its own mod, and its own runs; the {@code gametest} source set and its runs never see the extra mods or the
 * suite's sources.
 */
public final class IntegrationGameTestsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.integration-gametests";
    public static final String EXTENSION_NAME = "integrationGameTests";
    private static final String GAMETEST_SOURCE_SET = "gametest";
    private static final String GAMETEST_RUN = "gameTest";
    private static final String FABRIC_FILTER_PROPERTY = "fabric-api.gametest.filter";
    private static final String FABRIC_PRODUCTION_SERVER_TASK = "runProductionServerGameTest";
    /** production-gametests' single-loader aggregate; multi-loader builds use the root runAllGameTests instead. */
    private static final String PRODUCTION_AGGREGATE_TASK = "runAllProductionGameTests";

    @Override
    public void apply(Project project) {
        NamedDomainObjectContainer<IntegrationGameTestSuite> suites =
                project.getObjects().domainObjectContainer(IntegrationGameTestSuite.class);
        project.getExtensions().add(EXTENSION_NAME, suites);
        project.afterEvaluate(_ -> suites.forEach(suite -> configureSuite(project, suite)));
        // Registered once production-gametests is applied, so this runs after its afterEvaluate creates the
        // GameTest JAR, the ordinary production server run and the single-loader aggregate, whichever plugin is
        // applied first.
        project.getPluginManager().withPlugin(ProductionGameTestsPlugin.PLUGIN_ID, _ -> project.afterEvaluate(_ -> {
            if (!project.getTasks().getNames().contains(FABRIC_PRODUCTION_SERVER_TASK)) {
                return;
            }
            suites.forEach(suite -> registerFabricProductionRun(project, suite));
            if (project.getTasks().getNames().contains(PRODUCTION_AGGREGATE_TASK)) {
                project.getTasks().named(PRODUCTION_AGGREGATE_TASK).configure(task -> suites.forEach(suite ->
                        task.dependsOn(suite.getProductionRunTaskName())));
            }
        }));
    }

    /** True for an Architectury Loom NeoForge project ({@code loom.platform=neoforge}). */
    public static boolean isNeoForge(Project project) {
        return "neoforge".equalsIgnoreCase(String.valueOf(project.findProperty("loom.platform")).strip());
    }

    /** The extra mods and the suite's own mod JAR, as the production runs load them. */
    public static FileCollection suiteMods(Project project, IntegrationGameTestSuite suite) {
        return project.files(
                project.getTasks().named(suite.getJarTaskName(), Jar.class).flatMap(Jar::getArchiveFile),
                project.getConfigurations().named(suite.getModsConfigurationName())
        );
    }

    public static String filter(IntegrationGameTestSuite suite) {
        String filter = suite.getFilter().getOrNull();
        if (filter == null || filter.isBlank()) {
            throw new GradleException(EXTENSION_NAME + "." + suite.getName() + ".filter is required: a GameTest"
                    + " selector such as 'examplemod:compat_*'.");
        }
        return filter.strip();
    }

    private static void configureSuite(Project project, IntegrationGameTestSuite suite) {
        String name = suite.getName();
        String filter = filter(suite);
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet gameTest = sourceSets.findByName(GAMETEST_SOURCE_SET);
        LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
        RunConfigSettings gameTestRun = loom.getRuns().findByName(GAMETEST_RUN);
        if (gameTest == null || gameTestRun == null) {
            throw new GradleException(EXTENSION_NAME + "." + name + " needs the '" + GAMETEST_SOURCE_SET
                    + "' source set and Loom's '" + GAMETEST_RUN + "' server GameTest run.");
        }

        Configuration mods = project.getConfigurations().create(suite.getModsConfigurationName(), configuration -> {
            configuration.setDescription("Extra mods loaded by the " + name + " integration GameTests.");
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);
        });
        suite.getMods().get().stream()
                .filter(coordinate -> !coordinate.isBlank())
                .forEach(coordinate -> project.getDependencies().add(mods.getName(), coordinate.strip()));
        suite.getGitMods().get().forEach(gitMod ->
                project.getDependencies().add(mods.getName(), project.files(gitModJar(project, gitMod))));

        SourceSet sources = sourceSets.create(name);
        suite.getSourceRoots().getFiles().forEach(root -> {
            sources.getJava().srcDir(new File(root, "java"));
            sources.getResources().srcDir(new File(root, "resources"));
        });
        sources.setCompileClasspath(gameTest.getOutput().plus(gameTest.getCompileClasspath()).plus(mods));
        TaskProvider<Jar> jar = project.getTasks().register(suite.getJarTaskName(), Jar.class, task -> {
            task.setGroup("build");
            task.setDescription("Packages the " + name + " integration GameTests as their own mod.");
            task.getArchiveClassifier().set(name + "-gametest");
            task.from(sources.getOutput());
        });
        // The suite is loaded as a JAR: its classes and resources stay one mod on both loaders.
        sources.setRuntimeClasspath(gameTest.getRuntimeClasspath().plus(mods).plus(project.files(jar)));
        // `check` runs Checkstyle on every source set; this configuration needs no compiled classes, so a plain
        // build checks the suite's sources without compiling them against the extra mods.
        project.getTasks().withType(Checkstyle.class)
                .matching(task -> task.getName().equals(sources.getTaskName("checkstyle", null)))
                .configureEach(task -> task.setClasspath(project.files()));

        String runDir = "build/run/" + suite.getRunName();
        loom.getRuns().create(suite.getRunName(), run -> {
            run.inherit(gameTestRun);
            run.name((isNeoForge(project) ? "NeoForge " : "Fabric ") + name + " integration Game Tests");
            run.source(sources);
            run.runDir(runDir);
            if (isNeoForge(project)) {
                run.programArgs("--tests", filter);
            } else {
                run.property(FABRIC_FILTER_PROPERTY, filter);
            }
        });
        project.getTasks().named(suite.getRunTaskName()).configure(task -> {
            task.setGroup("verification");
            task.setDescription("Runs the " + name + " integration GameTests (" + filter + ") with their extra mods.");
            task.dependsOn(sources.getRuntimeClasspath());
            task.doFirst("resetGameTestWorld", _ -> project.delete(project.file(runDir + "/world")));
        });
    }

    private static void registerFabricProductionRun(Project project, IntegrationGameTestSuite suite) {
        String filter = filter(suite);
        ServerGameTestProductionRunTask ordinary = project.getTasks()
                .named(FABRIC_PRODUCTION_SERVER_TASK, ServerGameTestProductionRunTask.class).get();
        project.getTasks().register(suite.getProductionRunTaskName(), ServerGameTestProductionRunTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the " + suite.getName() + " integration GameTests (" + filter
                    + ") with their extra mods in Loom's production server environment.");
            task.getRunDir().convention(project.getLayout().getBuildDirectory()
                    .dir("run/" + uncapitalized(suite.getProductionRunTaskName().substring("run".length()))));
            // doFirst prepends: the world reset runs first, then the EULA is written, then Loom launches the server.
            task.doFirst("acceptEula", _ -> writeEula(task.getRunDir().get()));
            task.doFirst("resetGameTestWorld", _ -> task.resetWorld());
            task.getMods().from(ordinary.getMods());
            task.getMods().from(suiteMods(project, suite));
            task.getRuntimeLibraries().from(ordinary.getRuntimeLibraries());
            task.includeRuntimeLibrariesInClasspath();
            task.getJvmArgs().addAll(ordinary.getJvmArgs());
            task.getJvmArgs().add("-D" + FABRIC_FILTER_PROPERTY + "=" + filter);
            task.getProgramArgs().addAll(ordinary.getProgramArgs());
            task.getInstallerVersion().convention(ordinary.getInstallerVersion());
        });
    }

    private static void writeEula(Directory runDir) {
        Path eula = runDir.file("eula.txt").getAsFile().toPath();
        try {
            Files.createDirectories(eula.getParent());
            Files.writeString(eula, "eula=true\n");
        } catch (IOException exception) {
            throw new GradleException("Cannot write " + eula + ".", exception);
        }
    }

    /**
     * The JARs of {@code gitMod} for this loader, built once per build by a root-project task shared by every
     * project and suite that uses the same commit. The checkout is kept in the root project's {@code .gradle}.
     */
    private static Provider<?> gitModJar(Project project, GitMod gitMod) {
        Project root = project.getRootProject();
        String taskName = "buildGitMod" + capitalized(gitMod.name()) + gitMod.shortCommit();
        TaskProvider<BuildGitModTask> build = root.getTasks().getNames().contains(taskName)
                ? root.getTasks().named(taskName, BuildGitModTask.class)
                : root.getTasks().register(taskName, BuildGitModTask.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Builds the release JARs of " + gitMod.repository() + " at " + gitMod.commit() + ".");
                    task.getRepository().set(gitMod.repository());
                    task.getCommit().set(gitMod.commit());
                    task.getCheckoutDirectory().set(root.getLayout().getProjectDirectory()
                            .dir(".gradle/minecraftmodconventions/git-mods/" + gitMod.name() + "/" + gitMod.commit()));
                    task.getReleaseJarsDirectory().set(task.getCheckoutDirectory().dir("build/libs"));
                });
        boolean neoForge = isNeoForge(project);
        return build.flatMap(BuildGitModTask::getReleaseJarsDirectory).map(directory -> directory.getAsFileTree()
                .matching(pattern -> {
                    if (neoForge) {
                        pattern.include("*-neoforge-*.jar");
                    } else {
                        pattern.include("*.jar").exclude("*-neoforge-*.jar");
                    }
                }));
    }

    private static String uncapitalized(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String capitalized(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
