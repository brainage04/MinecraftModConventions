package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Installs a NeoForge dedicated server with the official installer ({@code --install-server}). */
@DisableCachingByDefault(because = "The installation is large and machine-specific; it stays in the build directory.")
public abstract class InstallNeoForgeServerTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getInstaller();

    @Input
    public abstract Property<String> getNeoForgeVersion();

    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    @OutputDirectory
    public abstract DirectoryProperty getServerDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /** The launcher arguments file the installer writes for this platform. */
    static Path launcherArguments(Path serverDirectory, String neoForgeVersion) {
        String file = System.getProperty("os.name", "").startsWith("Windows") ? "win_args.txt" : "unix_args.txt";
        return serverDirectory.resolve("libraries/net/neoforged/neoforge").resolve(neoForgeVersion).resolve(file);
    }

    @TaskAction
    public void install() throws IOException {
        File serverDirectory = getServerDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(serverDirectory));
        Files.createDirectories(serverDirectory.toPath());

        // The installer writes <installer>.log beside itself, so it runs from a copy outside Gradle's cache.
        File installer = getInstaller().getSingleFile();
        Path workingCopy = getTemporaryDir().toPath().resolve(installer.getName());
        Files.copy(installer.toPath(), workingCopy, StandardCopyOption.REPLACE_EXISTING);
        Path log = getTemporaryDir().toPath().resolve(installer.getName() + ".log");

        getLogger().lifecycle("Installing the NeoForge {} server into {}", getNeoForgeVersion().get(), serverDirectory);
        ExecResult result;
        try (OutputStream output = Files.newOutputStream(getTemporaryDir().toPath().resolve("installer-output.txt"))) {
            result = getExecOperations().exec(spec -> {
                spec.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile());
                spec.setArgs(List.of("-jar", workingCopy.toString(), "--install-server", serverDirectory.getAbsolutePath()));
                spec.setWorkingDir(getTemporaryDir());
                spec.setStandardOutput(output);
                spec.setErrorOutput(output);
                spec.setIgnoreExitValue(true);
            });
        }
        Path arguments = launcherArguments(serverDirectory.toPath(), getNeoForgeVersion().get());
        if (result.getExitValue() != 0 || !Files.isRegularFile(arguments)) {
            throw new GradleException("The NeoForge " + getNeoForgeVersion().get() + " installer failed (exit code "
                    + result.getExitValue() + "); see " + log + " and "
                    + getTemporaryDir().toPath().resolve("installer-output.txt") + ".");
        }
    }
}
