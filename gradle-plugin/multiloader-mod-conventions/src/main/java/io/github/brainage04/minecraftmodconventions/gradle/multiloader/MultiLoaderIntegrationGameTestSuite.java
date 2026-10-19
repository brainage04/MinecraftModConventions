package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import io.github.brainage04.minecraftmodconventions.gradle.integration.GitMod;
import org.gradle.api.Named;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * An integration GameTest suite of a multi-loader mod: its sources are {@code common/src/<name>} plus the loaders'
 * own {@code fabric/src/<name>} and {@code neoforge/src/<name>}, and both loaders run it with their extra mods.
 */
public abstract class MultiLoaderIntegrationGameTestSuite implements Named {
    private final String name;

    @Inject
    public MultiLoaderIntegrationGameTestSuite(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** GameTest selector such as {@code examplemod:compat_*}; required. */
    public abstract Property<String> getFilter();

    /** Maven coordinates of the extra Fabric mods, resolved without their transitive dependencies. */
    public abstract ListProperty<String> getFabricMods();

    /** Maven coordinates of the extra NeoForge mods, resolved without their transitive dependencies. */
    public abstract ListProperty<String> getNeoForgeMods();

    /** Extra mods built from pinned commits of multi-loader mod repositories; each loader loads its own JAR. */
    public abstract ListProperty<GitMod> getGitMods();

    /** Adds the release JARs built from {@code commit} of the multi-loader mod repository {@code repository}. */
    public void gitMod(String repository, String commit) {
        getGitMods().add(new GitMod(repository, commit));
    }
}
