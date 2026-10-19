package io.github.brainage04.minecraftmodconventions.gradle.integration;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * A named GameTest suite that runs with extra mods loaded. Its sources are the source set of the same name
 * ({@code src/<name>/java} and {@code src/<name>/resources}, plus every {@link #getSourceRoots() source root}); it
 * compiles against the {@code gametest} source set and runs only the GameTests matching {@link #getFilter()}.
 */
public abstract class IntegrationGameTestSuite implements Named {
    private final String name;

    @Inject
    public IntegrationGameTestSuite(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** GameTest selector such as {@code examplemod:compat_*}; required. */
    public abstract Property<String> getFilter();

    /** Maven coordinates of the extra mods, resolved without their transitive dependencies. */
    public abstract ListProperty<String> getMods();

    /** Extra mods built from pinned commits of multi-loader mod repositories. */
    public abstract ListProperty<GitMod> getGitMods();

    /** Further directories holding {@code java} and {@code resources} for the suite's source set. */
    public abstract ConfigurableFileCollection getSourceRoots();

    /** Adds the release JAR built from {@code commit} of the multi-loader mod repository {@code repository}. */
    public void gitMod(String repository, String commit) {
        getGitMods().add(new GitMod(repository, commit));
    }

    /** The configuration holding the extra mods: {@code <name>Mods}. */
    public String getModsConfigurationName() {
        return name + "Mods";
    }

    /** Packages the suite's source set as its own mod: {@code <name>GameTestJar}. */
    public String getJarTaskName() {
        return name + "GameTestJar";
    }

    /** The development run configuration: {@code <name>GameTest}. */
    public String getRunName() {
        return name + "GameTest";
    }

    /** The development run task: {@code run<Name>GameTest}. */
    public String getRunTaskName() {
        return "run" + capitalized() + "GameTest";
    }

    /** The production server run task: {@code runProduction<Name>GameTest}. */
    public String getProductionRunTaskName() {
        return "runProduction" + capitalized() + "GameTest";
    }

    private String capitalized() {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
