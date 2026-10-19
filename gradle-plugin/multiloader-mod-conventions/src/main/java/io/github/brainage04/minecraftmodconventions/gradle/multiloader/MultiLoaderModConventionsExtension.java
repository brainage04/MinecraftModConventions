package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class MultiLoaderModConventionsExtension {
    private final Property<Boolean> fabricClientGameTests;
    private final Property<Boolean> fabricServerGameTests;
    private final Property<Boolean> neoForgeGameTests;
    private final Property<Boolean> publishing;
    private final Property<Boolean> devAuth;
    private final NamedDomainObjectContainer<MultiLoaderIntegrationGameTestSuite> integrationGameTests;

    @Inject
    public MultiLoaderModConventionsExtension(ObjectFactory objects) {
        fabricClientGameTests = objects.property(Boolean.class).convention(true);
        fabricServerGameTests = objects.property(Boolean.class).convention(true);
        neoForgeGameTests = objects.property(Boolean.class).convention(true);
        publishing = objects.property(Boolean.class).convention(true);
        devAuth = objects.property(Boolean.class);
        integrationGameTests = objects.domainObjectContainer(MultiLoaderIntegrationGameTestSuite.class);
    }

    public Property<Boolean> getFabricClientGameTests() {
        return fabricClientGameTests;
    }

    public Property<Boolean> getFabricServerGameTests() {
        return fabricServerGameTests;
    }

    public Property<Boolean> getNeoForgeGameTests() {
        return neoForgeGameTests;
    }

    public Property<Boolean> getPublishing() {
        return publishing;
    }

    /** Adds DevAuth to the Fabric and NeoForge development runtime; defaults to true unless {@code mod_side=server}. */
    public Property<Boolean> getDevAuth() {
        return devAuth;
    }

    /** Named GameTest suites that run on both loaders with extra mods loaded; see the README. */
    public NamedDomainObjectContainer<MultiLoaderIntegrationGameTestSuite> getIntegrationGameTests() {
        return integrationGameTests;
    }

    public void integrationGameTests(Action<? super NamedDomainObjectContainer<MultiLoaderIntegrationGameTestSuite>> action) {
        action.execute(integrationGameTests);
    }
}
