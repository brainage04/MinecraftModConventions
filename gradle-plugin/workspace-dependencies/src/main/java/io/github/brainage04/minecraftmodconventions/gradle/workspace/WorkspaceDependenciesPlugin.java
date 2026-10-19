package io.github.brainage04.minecraftmodconventions.gradle.workspace;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.Directory;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-local development conveniences: local-first sibling Maven repositories with Maven Central fallback, and a
 * shared Minecraft options file for development clients.
 */
public final class WorkspaceDependenciesPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.workspace-dependencies";

    @Override
    public void apply(Project project) {
        WorkspaceDependenciesExtension extension = project.getExtensions().create(
                "workspaceDependencies",
                WorkspaceDependenciesExtension.class,
                project.getObjects(),
                project.getLayout()
        );
        RepositoryState state = new RepositoryState();
        Runnable configureRepositories = () -> configureRepositories(project, extension, state);

        project.getConfigurations().configureEach(configuration ->
                configuration.getIncoming().beforeResolve(_ -> {
                    configureRepositories.run();
                    state.repositoriesMayHaveBeenUsed = true;
                }));
        project.afterEvaluate(_ -> configureRepositories.run());
        DevClientOptions.configure(project);
    }

    private static void configureRepositories(
            Project project,
            WorkspaceDependenciesExtension extension,
            RepositoryState state
    ) {
        RepositoryHandler repositories = project.getRepositories();
        List<MavenArtifactRepository> localRepositories = new ArrayList<>();

        for (WorkspaceDependencyDeclaration declaration : extension.getDeclarations()) {
            MavenCoordinate coordinate = coordinate(declaration);
            MavenArtifactRepository repository = state.declarationRepositories.get(declaration);
            if (repository == null) {
                URI repositoryUri = repositoryDirectory(declaration).toURI();
                repository = state.primaryRepositories.get(repositoryUri);
                if (repository == null || state.repositoriesMayHaveBeenUsed) {
                    repository = repositories.maven(candidate -> {
                        candidate.setName(repositoryName(declaration.getName()));
                        candidate.setUrl(repositoryUri);
                    });
                    state.primaryRepositories.putIfAbsent(repositoryUri, repository);
                }
                repository.content(content ->
                        content.includeModule(coordinate.group(), coordinate.artifact())
                );
                state.declarationRepositories.put(declaration, repository);
            }
            if (!localRepositories.contains(repository)) {
                localRepositories.add(repository);
            }
        }

        // Only move repositories that are not already in the leading block of local repositories.
        // Re-adding a repository fires every repositories.all hook again, and Gradle's exclusiveContent
        // hook then mutates its content filter, which fails once the repository has been used.
        int leading = 0;
        while (leading < repositories.size() && localRepositories.contains(repositories.get(leading))) {
            leading++;
        }
        List<ArtifactRepository> positioned = new ArrayList<>(repositories.subList(0, leading));
        for (int index = localRepositories.size() - 1; index >= 0; index--) {
            MavenArtifactRepository repository = localRepositories.get(index);
            if (!positioned.contains(repository)) {
                repositories.remove(repository);
                repositories.addFirst(repository);
            }
        }

        boolean centralPresent = repositories.withType(MavenArtifactRepository.class).stream()
                .anyMatch(repository -> repository.getUrl().equals(URI.create(RepositoryHandler.MAVEN_CENTRAL_URL)));
        if (!centralPresent) {
            repositories.mavenCentral();
        }
    }


    private static MavenCoordinate coordinate(WorkspaceDependencyDeclaration declaration) {
        String notation = declaration.getCoordinate().getOrNull();
        if (notation == null) {
            throw new GradleException(
                    "Workspace dependency '" + declaration.getName() + "' requires a coordinate."
            );
        }
        String[] parts = notation.split(":", -1);
        if (parts.length != 3
                || parts[0].isBlank()
                || parts[1].isBlank()
                || parts[2].isBlank()) {
            throw new GradleException(
                    "Workspace dependency '" + declaration.getName()
                            + "' coordinate must use nonblank group:artifact:version notation, but was '"
                            + notation + "'."
            );
        }
        return new MavenCoordinate(parts[0].strip(), parts[1].strip(), parts[2].strip());
    }

    private static File repositoryDirectory(WorkspaceDependencyDeclaration declaration) {
        String relativePath = declaration.getLocalRepository().get().strip();
        if (relativePath.isEmpty()) {
            throw new GradleException(
                    "Workspace dependency '" + declaration.getName() + "' localRepository cannot be blank."
            );
        }
        Directory siblingDirectory = declaration.getSiblingDirectory().get();
        return siblingDirectory.dir(relativePath).getAsFile();
    }

    private static String repositoryName(String declarationName) {
        StringBuilder normalized = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < declarationName.length(); index++) {
            char character = declarationName.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                capitalize = true;
                continue;
            }
            normalized.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = false;
        }
        if (normalized.isEmpty()) {
            normalized.append("Dependency");
        }
        return "Workspace" + normalized + "Local";
    }

    private static final class RepositoryState {
        private final Map<WorkspaceDependencyDeclaration, MavenArtifactRepository> declarationRepositories =
                new IdentityHashMap<>();
        private final Map<URI, MavenArtifactRepository> primaryRepositories = new LinkedHashMap<>();
        private boolean repositoriesMayHaveBeenUsed;
    }

    private record MavenCoordinate(String group, String artifact, String version) {
    }
}
