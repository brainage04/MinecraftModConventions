package io.github.brainage04.minecraftmodconventions.gradle.production;

import net.fabricmc.loom.task.prod.ServerProductionRunTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.Classpath;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

/** Production server GameTest task with an explicit non-mod runtime library classpath. */
@DisableCachingByDefault(because = "Runs a production Minecraft server process.")
public abstract class ServerGameTestProductionRunTask extends ServerProductionRunTask {
    public ServerGameTestProductionRunTask() {
        // A GameTest run checks the environment as well as its declared inputs (for example a test filter
        // passed through JAVA_TOOL_OPTIONS), so every invocation must start the server.
        getOutputs().upToDateWhen(task -> false);
    }

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeLibraries();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /** Deletes only the saved dedicated-server world, preserving the rest of the run directory. */
    public final void resetWorld() {
        getFileSystemOperations().delete(spec -> spec.delete(getRunDir().dir("world")));
    }

    public final void includeRuntimeLibrariesInClasspath() {
        getClasspath().from(getRuntimeLibraries());
    }

}
