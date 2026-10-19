package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.List;

public abstract class ProductionGameTestExtension {
    private final Property<Boolean> includeClient;
    private final Property<Boolean> includeServer;
    private final Property<Boolean> includeFabricApiDependency;
    private final Property<String> fabricApiVersionProperty;
    private final ListProperty<String> runtimeModDependencies;
    private final ListProperty<String> runtimeLibraryDependencies;
    private final DirectoryProperty clientRunDir;
    private final DirectoryProperty serverRunDir;
    private final Property<Boolean> clientUseXvfb;
    private final Property<Boolean> clientSilenceAudio;
    private final Property<Boolean> disableClientNetworkSynchronizer;
    private final ListProperty<String> clientJvmArgs;
    private final ListProperty<String> clientProgramArgs;
    private final ListProperty<String> serverJvmArgs;
    private final ListProperty<String> serverProgramArgs;
    private final Property<String> serverInstallerVersion;

    @Inject
    public ProductionGameTestExtension(ObjectFactory objects, ProjectLayout layout) {
        includeClient = objects.property(Boolean.class).convention(true);
        includeServer = objects.property(Boolean.class).convention(true);
        includeFabricApiDependency = objects.property(Boolean.class).convention(true);
        runtimeModDependencies = objects.listProperty(String.class).convention(List.of());
        runtimeLibraryDependencies = objects.listProperty(String.class).convention(List.of());
        fabricApiVersionProperty = objects.property(String.class).convention("fabric_api_version");
        clientRunDir = objects.directoryProperty().convention(layout.getBuildDirectory().dir("run/productionClientGameTest"));
        serverRunDir = objects.directoryProperty().convention(layout.getBuildDirectory().dir("run/productionServerGameTest"));
        clientUseXvfb = objects.property(Boolean.class).convention(true);
        clientSilenceAudio = objects.property(Boolean.class).convention(true);
        disableClientNetworkSynchronizer = objects.property(Boolean.class).convention(true);
        clientJvmArgs = objects.listProperty(String.class).convention(List.of());
        clientProgramArgs = objects.listProperty(String.class).convention(List.of());
        serverJvmArgs = objects.listProperty(String.class).convention(List.of());
        serverProgramArgs = objects.listProperty(String.class).convention(List.of());
        serverInstallerVersion = objects.property(String.class);
    }


    public Property<Boolean> getIncludeClient() {
        return includeClient;
    }

    public Property<Boolean> getIncludeServer() {
        return includeServer;
    }

    public Property<Boolean> getIncludeFabricApiDependency() {
        return includeFabricApiDependency;
    }

    public Property<String> getFabricApiVersionProperty() {
        return fabricApiVersionProperty;
    }

    public DirectoryProperty getClientRunDir() {
        return clientRunDir;
    }

    public ListProperty<String> getRuntimeModDependencies() {
        return runtimeModDependencies;
    }

    public ListProperty<String> getRuntimeLibraryDependencies() {
        return runtimeLibraryDependencies;
    }

    public DirectoryProperty getServerRunDir() {
        return serverRunDir;
    }

    public Property<Boolean> getClientUseXvfb() {
        return clientUseXvfb;
    }

    /** Forces OpenAL's null driver for the production client; a recorder that routes audio turns it off. */
    public Property<Boolean> getClientSilenceAudio() {
        return clientSilenceAudio;
    }

    public Property<Boolean> getDisableClientNetworkSynchronizer() {
        return disableClientNetworkSynchronizer;
    }

    public ListProperty<String> getClientJvmArgs() {
        return clientJvmArgs;
    }

    public ListProperty<String> getClientProgramArgs() {
        return clientProgramArgs;
    }

    public ListProperty<String> getServerJvmArgs() {
        return serverJvmArgs;
    }

    public ListProperty<String> getServerProgramArgs() {
        return serverProgramArgs;
    }

    public Property<String> getServerInstallerVersion() {
        return serverInstallerVersion;
    }
}
