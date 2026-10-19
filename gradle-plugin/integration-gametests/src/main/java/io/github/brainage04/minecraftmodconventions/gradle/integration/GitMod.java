package io.github.brainage04.minecraftmodconventions.gradle.integration;

import org.gradle.api.GradleException;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * A mod built from a pinned commit of a multi-loader mod repository: the repository is checked out at
 * {@code commit} and built with its own {@code ./gradlew collectReleaseArtifacts}, and each loader loads its own
 * release JAR from the checkout's {@code build/libs}.
 *
 * @param repository a Git URL or local path
 * @param commit the full 40-character commit hash
 */
public record GitMod(String repository, String commit) implements Serializable {
    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");

    public GitMod {
        if (repository == null || repository.isBlank()) {
            throw new GradleException("A Git mod needs a repository.");
        }
        if (commit == null || !FULL_COMMIT.matcher(commit).matches()) {
            throw new GradleException("Git mod " + repository + " must be pinned to a full 40-character commit hash, not '"
                    + commit + "'.");
        }
        repository = repository.strip();
    }

    /** The repository's last path segment without {@code .git}, for directory and task names. */
    public String name() {
        String path = repository.replaceAll("[/\\\\]+$", "");
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
                .replaceFirst("\\.git$", "")
                .replaceAll("[^A-Za-z0-9]", "");
        return name.isEmpty() ? "mod" : name;
    }

    public String shortCommit() {
        return commit.substring(0, 7);
    }
}
