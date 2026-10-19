package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a mod's NeoForge GameTests on a production NeoForge server installed by {@link InstallNeoForgeServerTask}.
 *
 * <p>The server starts through the installer's own launcher arguments with FML's {@code GameTestServer}
 * entrypoint in place of {@code Server}, which runs vanilla's GameTest server and exits with the number of failed
 * required tests. The release JAR and the GameTest JAR are loaded as one mod through FML's {@code fml.modFolders},
 * so the GameTest classes' {@code @EventBusSubscriber(modid = <mod_id>)} registrations reach the mod; the mods the
 * project declares in {@code productionRuntimeMods} go into {@code mods/}.
 */
@DisableCachingByDefault(because = "Runs a production Minecraft server process.")
public abstract class NeoForgeServerGameTestProductionRunTask extends DefaultTask {
    static final String SERVER_ENTRYPOINT = "net.neoforged.fml.startup.Server";
    static final String GAMETEST_SERVER_ENTRYPOINT = "net.neoforged.fml.startup.GameTestServer";
    private static final String MOD_UNDER_TEST_DIRECTORY = "mod-under-test";

    public NeoForgeServerGameTestProductionRunTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getServerDirectory();

    @Input
    public abstract Property<String> getNeoForgeVersion();

    @Input
    public abstract Property<String> getModId();

    /** The release JAR, as {@code collectReleaseArtifacts} ships it. */
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getModJar();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getGameTestJar();

    /** Other mods the server needs, copied into {@code mods/}. */
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeMods();

    @Input
    public abstract ListProperty<String> getJvmArgs();

    /** Extra arguments for vanilla's GameTest server, such as {@code --tests <selector>}. */
    @Input
    public abstract ListProperty<String> getProgramArgs();

    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    @Internal
    public abstract DirectoryProperty getRunDir();

    /** JUnit-like XML report written by vanilla's GameTest server. */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void run() throws IOException {
        Path serverDirectory = getServerDirectory().get().getAsFile().toPath().toAbsolutePath();
        Path runDir = getRunDir().get().getAsFile().toPath().toAbsolutePath();
        Path report = getReportFile().get().getAsFile().toPath().toAbsolutePath();
        Path modUnderTest = runDir.resolve(MOD_UNDER_TEST_DIRECTORY);

        getFileSystemOperations().delete(spec -> spec.delete(runDir.resolve("mods"), modUnderTest, report));
        getFileSystemOperations().copy(spec -> {
            spec.from(getRuntimeMods());
            spec.into(runDir.resolve("mods"));
        });
        getFileSystemOperations().copy(spec -> {
            spec.from(getModJar(), getGameTestJar());
            spec.into(modUnderTest);
        });
        Files.createDirectories(report.getParent());
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

        Path launcherArguments = InstallNeoForgeServerTask.launcherArguments(serverDirectory, getNeoForgeVersion().get());
        Path gameTestArguments = runDir.resolve("gametest_args.txt");
        Files.write(gameTestArguments, gameTestServerArguments(
                Files.readString(launcherArguments, StandardCharsets.UTF_8),
                serverDirectory.resolve("libraries"),
                launcherArguments
        ), StandardCharsets.UTF_8);

        String modId = getModId().get();
        String modFolders = String.join(File.pathSeparator,
                modId + "%%" + modUnderTest.resolve(getModJar().get().getAsFile().getName()),
                modId + "%%" + modUnderTest.resolve(getGameTestJar().get().getAsFile().getName()));
        List<String> arguments = new ArrayList<>(getJvmArgs().get());
        arguments.add("-Dfml.modFolders=" + modFolders);
        arguments.add("@" + gameTestArguments);
        arguments.add("--report");
        arguments.add(report.toString());
        arguments.addAll(getProgramArgs().get());

        getLogger().lifecycle("Running the NeoForge {} production GameTest server in {}", getNeoForgeVersion().get(), runDir);
        ExecResult result = getExecOperations().exec(spec -> {
            spec.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile());
            spec.setArgs(arguments);
            spec.setWorkingDir(runDir.toFile());
            spec.setStandardInput(InputStream.nullInputStream());
            spec.setIgnoreExitValue(true);
        });
        if (result.getExitValue() != 0) {
            throw new GradleException("NeoForge production server GameTests failed with exit code " + result.getExitValue()
                    + " (the number of failed required tests; 1 also means a crash, 255 a startup or test selection error)."
                    + " See " + runDir.resolve("logs/latest.log") + " and " + report + ".");
        }
    }

    /**
     * Rewrites the installer's launcher arguments for a GameTest server outside the installation: the main class
     * becomes FML's GameTest server entrypoint and every relative {@code libraries} path points into the
     * installation. Each argument is written quoted on its own line.
     */
    static List<String> gameTestServerArguments(String installerArguments, Path libraries, Path source) {
        Pattern relativeLibraries = Pattern.compile(
                "(^|[=" + Pattern.quote(File.pathSeparator) + "])libraries(?=[/\\\\" + Pattern.quote(File.pathSeparator) + "]|$)"
        );
        String replacement = "$1" + Matcher.quoteReplacement(libraries.toString());
        List<String> arguments = new ArrayList<>();
        boolean entrypointReplaced = false;
        for (String argument : Arrays.stream(installerArguments.split("\\s+")).filter(token -> !token.isEmpty()).toList()) {
            if (argument.indexOf('"') >= 0 || argument.indexOf('\'') >= 0) {
                throw new GradleException("Unexpected quoted argument in " + source + ": " + argument);
            }
            if (argument.equals(SERVER_ENTRYPOINT)) {
                argument = GAMETEST_SERVER_ENTRYPOINT;
                entrypointReplaced = true;
            }
            String rewritten = relativeLibraries.matcher(argument).replaceAll(replacement);
            arguments.add('"' + rewritten.replace("\\", "\\\\") + '"');
        }
        if (!entrypointReplaced) {
            throw new GradleException(source + " does not launch " + SERVER_ENTRYPOINT
                    + "; this NeoForge version's server launcher is not supported.");
        }
        return arguments;
    }
}
