package io.github.brainage04.minecraftmodconventions.gradle.central;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.plugins.signing.Sign;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

/** Configures reusable Maven Central publication metadata, signing, and Portal upload behavior. */
public final class MavenCentralPublishingPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.maven-central-publishing";
    public static final String EXTENSION_NAME = "mavenCentralPublishing";
    public static final String PUBLISH_TASK_NAME = "publishToMavenCentral";

    private static final String METADATA_VALIDATION_TASK_NAME = "validateMavenPublicationMetadata";
    private static final String CENTRAL_VALIDATION_TASK_NAME = "validateMavenCentralPublishing";
    private static final String LOCAL_REPOSITORY_NAME = "local";
    private static final String CENTRAL_REPOSITORY_NAME = "central";
    private static final URI CENTRAL_STAGING_REPOSITORY = URI.create(
            "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
    );

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(MavenPublishPlugin.class);
        project.getPluginManager().apply(SigningPlugin.class);

        MavenCentralPublishingExtension extension = project.getExtensions().create(
                EXTENSION_NAME,
                MavenCentralPublishingExtension.class
        );
        configureDefaults(project, extension);

        Provider<String> centralPortalUsername = project.getProviders()
                .environmentVariable("CENTRAL_PORTAL_USERNAME");
        Provider<String> centralPortalPassword = project.getProviders()
                .environmentVariable("CENTRAL_PORTAL_PASSWORD");
        Provider<String> signingKey = project.getProviders().environmentVariable("SIGNING_KEY");
        Provider<String> signingPassword = project.getProviders().environmentVariable("SIGNING_PASSWORD");
        Provider<Boolean> useGpgAgentSigning = project.getProviders()
                .gradleProperty("useGpgAgentSigning")
                .map(Boolean::parseBoolean)
                .orElse(false);

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        configurePublications(extension, publishing);
        configurePluginMarkerMetadata(project, publishing);
        configureRepositories(
                extension,
                publishing,
                centralPortalUsername,
                centralPortalPassword
        );
        configureSigning(
                project,
                publishing,
                signingKey,
                signingPassword,
                useGpgAgentSigning
        );
        configurePublishingTasks(
                project,
                extension,
                centralPortalUsername,
                centralPortalPassword,
                signingKey,
                signingPassword,
                useGpgAgentSigning
        );
    }

    private static void configureDefaults(Project project, MavenCentralPublishingExtension extension) {
        extension.getPublicationName().convention(project.getProviders().provider(() -> {
            Object modName = project.findProperty("mod_name");
            return modName == null ? project.getName() : modName.toString();
        }));
        extension.getNamespace().convention(
                project.getProviders().gradleProperty("centralNamespace")
                        .orElse(project.getProviders().provider(() -> project.getGroup().toString()))
        );
        extension.getPublishingType().convention(
                project.getProviders().gradleProperty("centralPublishingType").orElse("user_managed")
        );
        extension.getDeveloperId().convention("brainage04");
        extension.getDeveloperName().convention("brainage04");
        extension.getDeveloperUrl().convention("https://github.com/brainage04");
        extension.getLicenseName().convention("MIT License");
        extension.getLicenseUrl().convention("https://opensource.org/license/mit");
        extension.getLocalRepository().convention(project.getLayout().getBuildDirectory().dir("local-repo"));
    }

    private static void configurePublications(
            MavenCentralPublishingExtension extension,
            PublishingExtension publishing
    ) {
        publishing.getPublications().withType(MavenPublication.class).configureEach(publication ->
                publication.pom(pom -> {
                    pom.getName().convention(extension.getPublicationName());
                    pom.getDescription().convention(extension.getDescription());
                    pom.getUrl().set(extension.getRepository().map(MavenCentralPublishingPlugin::githubUrl));
                    pom.licenses(licenses -> licenses.license(license -> {
                        license.getName().set(extension.getLicenseName());
                        license.getUrl().set(extension.getLicenseUrl());
                        license.getDistribution().set("repo");
                    }));
                    pom.developers(developers -> developers.developer(developer -> {
                        developer.getId().set(extension.getDeveloperId());
                        developer.getName().set(extension.getDeveloperName());
                        developer.getUrl().set(extension.getDeveloperUrl());
                    }));
                    pom.scm(scm -> {
                        scm.getConnection().set(extension.getRepository().map(repository ->
                                "scm:git:" + githubUrl(repository) + ".git"));
                        scm.getDeveloperConnection().set(extension.getRepository().map(repository ->
                                "scm:git:ssh://git@github.com/"
                                        + ValidateMavenPublicationMetadataTask.normalizedRepository(repository)
                                        + ".git"));
                        scm.getUrl().set(extension.getRepository().map(MavenCentralPublishingPlugin::githubUrl));
                    });
                }));
    }

    private static void configurePluginMarkerMetadata(Project project, PublishingExtension publishing) {
        project.getPluginManager().withPlugin("java-gradle-plugin", ignored ->
                project.afterEvaluate(evaluatedProject -> {
                    GradlePluginDevelopmentExtension development = project.getExtensions()
                            .getByType(GradlePluginDevelopmentExtension.class);
                    development.getPlugins().forEach(declaration -> {
                        String publicationName = declaration.getName() + "PluginMarkerMaven";
                        if (!(publishing.getPublications().findByName(publicationName)
                                instanceof MavenPublication markerPublication)) {
                            return;
                        }
                        markerPublication.pom(pom -> {
                            if (declaration.getDisplayName() != null
                                    && !declaration.getDisplayName().isBlank()) {
                                pom.getName().set(declaration.getDisplayName());
                            }
                            if (declaration.getDescription() != null
                                    && !declaration.getDescription().isBlank()) {
                                pom.getDescription().set(declaration.getDescription());
                            }
                        });
                    });
                }));
    }

    private static void configureRepositories(
            MavenCentralPublishingExtension extension,
            PublishingExtension publishing,
            Provider<String> centralPortalUsername,
            Provider<String> centralPortalPassword
    ) {
        publishing.getRepositories().maven(repository -> {
            repository.setName(LOCAL_REPOSITORY_NAME);
            repository.setUrl(extension.getLocalRepository());
        });
        publishing.getRepositories().maven(repository -> {
            repository.setName(CENTRAL_REPOSITORY_NAME);
            repository.setUrl(CENTRAL_STAGING_REPOSITORY);
            repository.credentials(PasswordCredentials.class, credentials -> {
                credentials.setUsername(centralPortalUsername.getOrNull());
                credentials.setPassword(centralPortalPassword.getOrNull());
            });
        });
    }

    private static void configureSigning(
            Project project,
            PublishingExtension publishing,
            Provider<String> signingKey,
            Provider<String> signingPassword,
            Provider<Boolean> useGpgAgentSigning
    ) {
        SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        signing.setRequired((Callable<Boolean>) () -> project.getGradle().getTaskGraph().getAllTasks().stream()
                .anyMatch(MavenCentralPublishingPlugin::isCentralPublishingTask));
        if (useGpgAgentSigning.get()) {
            signing.useGpgCmd();
        } else if (signingKey.isPresent() && signingPassword.isPresent()) {
            signing.useInMemoryPgpKeys(signingKey.get(), signingPassword.get());
        }
        signing.sign(publishing.getPublications());

        project.getTasks().withType(Sign.class).configureEach(task -> task.doFirst(_ -> {
            if (signing.isRequired()
                    && !useGpgAgentSigning.get()
                    && (!signingKey.isPresent() || !signingPassword.isPresent())) {
                throw ValidateMavenCentralPublishingTask.missingSigningConfiguration();
            }
        }));
    }

    private static void configurePublishingTasks(
            Project project,
            MavenCentralPublishingExtension extension,
            Provider<String> centralPortalUsername,
            Provider<String> centralPortalPassword,
            Provider<String> signingKey,
            Provider<String> signingPassword,
            Provider<Boolean> useGpgAgentSigning
    ) {
        TaskProvider<ValidateMavenPublicationMetadataTask> validateMetadata = project.getTasks().register(
                METADATA_VALIDATION_TASK_NAME,
                ValidateMavenPublicationMetadataTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Validates Maven publication metadata.");
                    task.getPublicationName().convention(extension.getPublicationName());
                    task.getPublicationDescription().convention(extension.getDescription());
                    task.getRepository().convention(extension.getRepository());
                    task.getDeveloperId().convention(extension.getDeveloperId());
                    task.getDeveloperName().convention(extension.getDeveloperName());
                    task.getDeveloperUrl().convention(extension.getDeveloperUrl());
                    task.getLicenseName().convention(extension.getLicenseName());
                    task.getLicenseUrl().convention(extension.getLicenseUrl());
                }
        );
        TaskProvider<ValidateMavenCentralPublishingTask> validateCentral = project.getTasks().register(
                CENTRAL_VALIDATION_TASK_NAME,
                ValidateMavenCentralPublishingTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Validates credentials, signing, and Central release configuration.");
                    task.dependsOn(validateMetadata);
                    task.getNamespace().convention(extension.getNamespace());
                    task.getPublishingType().convention(extension.getPublishingType());
                    task.getCentralPortalUsername().convention(centralPortalUsername);
                    task.getCentralPortalPassword().convention(centralPortalPassword);
                    task.getSigningKey().convention(signingKey);
                    task.getSigningPassword().convention(signingPassword);
                    task.getUseGpgAgentSigning().convention(useGpgAgentSigning);
                }
        );
        TaskProvider<FinalizeMavenCentralDeploymentTask> publishToCentral = project.getTasks().register(
                PUBLISH_TASK_NAME,
                FinalizeMavenCentralDeploymentTask.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription(
                            "Publishes artifacts to Sonatype Central and uploads the staged deployment to the Central Portal."
                    );
                    task.getNamespace().convention(extension.getNamespace());
                    task.getPublishingType().convention(extension.getPublishingType());
                    task.getCentralPortalUsername().convention(centralPortalUsername);
                    task.getCentralPortalPassword().convention(centralPortalPassword);
                    task.getPublicationTaskPaths().convention(List.of());
                }
        );

        project.getTasks().withType(Sign.class).configureEach(task -> task.mustRunAfter(validateCentral));

        var publicationTasks = project.getTasks().withType(PublishToMavenRepository.class);
        publicationTasks.configureEach(task -> task.dependsOn(validateMetadata));
        var centralPublicationTasks = publicationTasks.matching(
                MavenCentralPublishingPlugin::isCentralPublishingTask
        );
        centralPublicationTasks.configureEach(task -> task.dependsOn(validateCentral));
        publishToCentral.configure(finalizer -> {
            finalizer.dependsOn(centralPublicationTasks);
            finalizer.getPublicationTaskPaths().set(project.getProviders().provider(() -> {
                String taskPathPrefix = ":".equals(project.getPath()) ? ":" : project.getPath() + ":";
                return centralPublicationTasks.getNames().stream()
                        .map(name -> taskPathPrefix + name)
                        .toList();
            }));
        });
    }


    private static boolean isCentralPublishingTask(Task task) {
        String name = task.getName();
        return PUBLISH_TASK_NAME.equals(name)
                || (name.startsWith("publish") && name.endsWith("PublicationToCentralRepository"));
    }


    private static String githubUrl(String repository) {
        return "https://github.com/" + ValidateMavenPublicationMetadataTask.normalizedRepository(repository);
    }

}
