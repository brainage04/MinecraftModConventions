package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecSpec;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The production client GameTests run against a plain vanilla dedicated server of the mod's Minecraft version: the
 * server is started (see {@link VanillaServer}) before the client and stopped after it, and the client gets the
 * server's address in {@value #SERVER_ADDRESS_ENV}. The client GameTests that join it come from the conventions'
 * {@code VanillaServerClientGameTests}; the run fails when no client joined, since then nothing was checked.
 */
@DisableCachingByDefault(because = "Runs a vanilla server and a production Minecraft client.")
public abstract class VanillaServerClientGameTestRunTask extends ClientGameTestProductionRunTask {
    /** The environment variable that carries {@code host:port} of the vanilla server to the client. */
    public static final String SERVER_ADDRESS_ENV = "MINECRAFTMODCONVENTIONS_VANILLA_SERVER";

    private String serverAddress;

    @Input
    public abstract Property<String> getMinecraftVersion();

    /** Where Mojang's version manifest is read; only tests change it. */
    @Input
    public abstract Property<String> getVersionManifestUrl();

    /** The server's maximum heap, as for {@code -Xmx}. */
    @Input
    public abstract Property<String> getServerMaxHeap();

    @Input
    public abstract Property<Integer> getServerStartupTimeoutSeconds();

    /** Shared by every project of the user: {@code <Gradle user home>/caches/minecraftmodconventions/vanilla-server}. */
    @Internal
    public abstract DirectoryProperty getServerJarCache();

    /** The server's directory, emptied before every run; its console is {@code console.log}. */
    @Internal
    public abstract DirectoryProperty getServerDir();

    @Override
    public void run() throws IOException {
        Path jar = VanillaServer.serverJar(
                getServerJarCache().get().getAsFile().toPath(),
                getMinecraftVersion().get(),
                URI.create(getVersionManifestUrl().get()));
        Path java = getJavaLauncher().map(JavaLauncher::getExecutablePath)
                .map(file -> file.getAsFile().toPath())
                .getOrElse(Path.of(System.getProperty("java.home"), "bin", "java"));
        List<String> joined;
        try (VanillaServer server = VanillaServer.start(
                java,
                jar,
                getServerDir().get().getAsFile().toPath(),
                getServerMaxHeap().get(),
                Duration.ofSeconds(getServerStartupTimeoutSeconds().get()))) {
            serverAddress = server.address();
            getLogger().lifecycle("Vanilla {} server ready on {} ({})",
                    getMinecraftVersion().get(), server.address(), server.directory());
            try {
                super.run();
            } finally {
                serverAddress = null;
                server.connectionEvents().forEach(line -> getLogger().lifecycle("vanilla server: {}", line));
            }
            joined = server.joinedPlayers();
        }
        if (joined.isEmpty()) {
            throw new GradleException("No client joined the vanilla server, so nothing was checked against it. "
                    + "Call VanillaServerClientGameTests from a client GameTest; see the MinecraftModConventions README.");
        }
    }

    @Override
    protected void configureCommand(ExecSpec exec) {
        super.configureCommand(exec);
        if (serverAddress == null) {
            throw new IllegalStateException("The vanilla server must be running before the client starts.");
        }
        exec.environment(SERVER_ADDRESS_ENV, serverAddress);
    }
}
