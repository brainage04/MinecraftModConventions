package io.github.brainage04.minecraftmodconventions.gradle.fabric;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;

import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactRepositoryContainer;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.api.tasks.testing.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies low-risk Fabric build defaults shared by convention consumers. */
public final class FabricModConventionsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.fabric-mod-conventions";
    public static final String CLIENT_GAMETEST_ENABLED_PROPERTY = "minecraftmodconventions.clientGameTest";
    public static final String MOD_SIDE_PROPERTY = "mod_side";

    private static final URI MINECRAFT_LIBRARIES = URI.create("https://libraries.minecraft.net");
    private static final URI FABRIC_MAVEN = URI.create("https://maven.fabricmc.net/");

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("net.fabricmc.fabric-loom");
        FabricModConventionsExtension extension = project.getExtensions().create(
                "fabricModConventions",
                FabricModConventionsExtension.class,
                project.getObjects(),
                project.getLayout()
        );

        ModSide modSide = ModSide.from(project);
        configureProjectIdentity(project);
        configureStandardDependencies(project);
        configureSourceLayout(project, modSide);
        configureUnitTests(project, modSide);
        LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
        // Read the run configuration at execution time so custom GameTest run directories are respected.
        project.getTasks().matching(task -> task.getName().equals("runGameTest"))
                .configureEach(task -> task.doFirst("resetGameTestWorld", _ ->
                        project.delete(project.file(loom.getRuns().getByName("gameTest").getRunDir())
                                .toPath().resolve("world"))));

        configureRepositoriesBeforeResolution(project, extension);
        project.afterEvaluate(_ -> configureConventions(project, extension));
    }


    private static void configureProjectIdentity(Project project) {
        project.setVersion(requiredProperty(project, "mod_version"));
        project.setGroup(requiredProperty(project, "maven_group"));
        project.getExtensions()
                .getByType(BasePluginExtension.class)
                .getArchivesName()
                .set(requiredProperty(project, "archives_base_name"));
    }

    private static void configureStandardDependencies(Project project) {
        DependencyHandler dependencies = project.getDependencies();
        dependencies.add("minecraft", "com.mojang:minecraft:" + requiredProperty(project, "minecraft_version"));
        dependencies.add("implementation", "net.fabricmc:fabric-loader:" + requiredProperty(project, "loader_version"));
        dependencies.add(
                "implementation",
                "net.fabricmc.fabric-api:fabric-api:" + requiredProperty(project, "fabric_api_version")
        );
        dependencies.add(
                "testImplementation",
                "net.fabricmc:fabric-loader-junit:" + requiredProperty(project, "loader_version")
        );
    }

    private static void configureSourceLayout(Project project, ModSide modSide) {
        LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
        String modId = requiredProperty(project, "mod_id");
        // Idempotent on purpose: the multi-loader plug-in also needs the split, and calling
        // splitEnvironmentSourceSets twice aborts plugin application with "The value for extension
        // 'loom' property 'minecraftJarConfiguration' is final and cannot be changed any further".
        SourceSetContainer existingSourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        if (modSide == ModSide.BOTH && existingSourceSets.findByName("client") == null) {
            loom.splitEnvironmentSourceSets();
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            SourceSet client = sourceSets.getByName("client");
            loom.getMods().register(modId, mod -> {
                mod.sourceSet(main);
                mod.sourceSet(client);
            });
        }

        var accessWidener = project.file("src/main/resources/" + modId + ".accesswidener");
        if (accessWidener.isFile()) {
            loom.getAccessWidenerPath().fileValue(accessWidener);
        }
    }

    private static void configureUnitTests(Project project, ModSide modSide) {
        project.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            task.systemProperty("fabric.side", modSide == ModSide.CLIENT ? "client" : "server");
        });
    }

    private static void configureConventions(Project project, FabricModConventionsExtension extension) {
        if (extension.getJavaEnabled().get()) {
            configureJava(project);
        }
        if (extension.getSourcesJarEnabled().get()) {
            project.getExtensions().getByType(JavaPluginExtension.class).withSourcesJar();
        }
        if (extension.getResourceExpansionEnabled().get()) {
            configureResourceExpansion(project, extension);
        }
        if (extension.getLicenseJarEnabled().get()) {
            configureLicenseJar(project, extension);
        }
    }

    private static void configureRepositoriesBeforeResolution(Project project, FabricModConventionsExtension extension) {
        AtomicBoolean configured = new AtomicBoolean();
        project.getConfigurations().configureEach(configuration ->
                configuration.getIncoming().beforeResolve(_ -> {
                    if (configured.compareAndSet(false, true) && extension.getRepositoriesEnabled().get()) {
                        configureRepositories(project);
                    }
                }));
    }

    private static void configureRepositories(Project project) {
        RepositoryHandler repositories = project.getRepositories();
        if (repositories.findByName(ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME) == null) {
            repositories.mavenCentral();
        }
        addMavenRepository(repositories, "MinecraftLibraries", MINECRAFT_LIBRARIES);
        addMavenRepository(repositories, "Fabric", FABRIC_MAVEN);
    }

    private static void addMavenRepository(RepositoryHandler repositories, String name, URI url) {
        boolean alreadyPresent = repositories.withType(MavenArtifactRepository.class).stream()
                .anyMatch(repository -> repository.getUrl().equals(url));
        if (!alreadyPresent) {
            repositories.maven(repository -> {
                repository.setName(name);
                repository.setUrl(url);
            });
        }
    }

    private static void configureJava(Project project) {
        int release = requiredJavaVersion(project);
        JavaVersion javaVersion = JavaVersion.toVersion(release);
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(javaVersion);
        java.setTargetCompatibility(javaVersion);
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(release);
            task.getOptions().setEncoding("UTF-8");
        });
    }

    private static int requiredJavaVersion(Project project) {
        String value = requiredProperty(project, "java_version");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new GradleException("Project property 'java_version' must be an integer, but was '" + value + "'.", exception);
        }
    }

    private static String requiredProperty(Project project, String name) {
        Object value = project.findProperty(name);
        if (value == null || value.toString().isBlank()) {
            throw new GradleException(PLUGIN_ID + " requires project property '" + name + "'.");
        }
        return value.toString().strip();
    }

    private static void configureResourceExpansion(Project project, FabricModConventionsExtension extension) {
        Provider<Map<String, String>> expansion = project.getProviders().provider(() -> {
            List<String> propertyNames = new ArrayList<>(extension.getFabricModJsonProperties().get());
            propertyNames.addAll(extension.getAdditionalFabricModJsonProperties().get());
            return resourceExpansion(project, propertyNames);
        });
        project.getTasks().withType(ProcessResources.class).configureEach(task -> {
            task.getInputs().property("fabricModConventions.fabricModJsonExpansion", expansion);
            task.filesMatching("fabric.mod.json", details -> details.expand(expansion.get()));
        });
    }

    private static Map<String, String> resourceExpansion(Project project, List<String> propertyNames) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String rawName : propertyNames) {
            String name = rawName == null ? "" : rawName.strip();
            if (name.isEmpty()) {
                throw new GradleException("fabricModConventions.fabricModJsonProperties cannot contain blank names.");
            }
            Object value = name.equals("mod_version") ? project.getVersion() : project.findProperty(name);
            if (value == null || value.toString().isBlank()) {
                throw new GradleException("fabric.mod.json expansion requires project property '" + name + "'.");
            }
            values.put(name, value.toString());
        }
        return Collections.unmodifiableMap(values);
    }

    private static void configureLicenseJar(Project project, FabricModConventionsExtension extension) {
        Provider<String> archivesName = project.getExtensions()
                .getByType(BasePluginExtension.class)
                .getArchivesName();
        project.getTasks().named("jar", Jar.class).configure(task -> {
            task.getInputs().property("fabricModConventions.archivesName", archivesName);
            task.from(extension.getLicenseFile(), spec ->
                    spec.rename(fileName -> fileName + "_" + archivesName.get()));
        });
    }
}
