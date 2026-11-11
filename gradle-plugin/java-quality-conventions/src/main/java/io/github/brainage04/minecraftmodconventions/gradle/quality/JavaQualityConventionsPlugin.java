package io.github.brainage04.minecraftmodconventions.gradle.quality;

import com.diffplug.gradle.spotless.SpotlessExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.util.ArrayList;
import java.util.List;

/** Configures one staged Java quality policy across every Java project in a build. */
public final class JavaQualityConventionsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.java-quality-conventions";
    /**
     * The default of {@code -PstrictQuality}: formatting, Checkstyle, and deprecation, unchecked and
     * rawtypes warnings as errors. {@code -PstrictQuality=false} turns all of it off.
     */
    static final boolean DEFAULT_STRICT_QUALITY = true;
    /**
     * The default of {@code -PstrictUnchecked} while strict quality is on: whether unchecked and
     * rawtypes warnings are errors too. {@code -PstrictUnchecked=false} keeps formatting, Checkstyle
     * and the deprecation group while leaving those two as warnings, which is the finer opt-out.
     */
    static final boolean DEFAULT_STRICT_UNCHECKED = true;

    private static final String GOOGLE_JAVA_FORMAT_VERSION = "1.36.1";
    private static final String CHECKSTYLE_VERSION = "13.9.0";
    private static final String CHECKSTYLE_CONFIG = """
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC
                    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                    "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
                <property name="charset" value="UTF-8"/>
                <property name="fileExtensions" value="java"/>
                <module name="TreeWalker">
                    <module name="AvoidStarImport"/>
                    <module name="EmptyStatement"/>
                    <module name="EqualsHashCode"/>
                    <module name="FallThrough"/>
                    <module name="OneStatementPerLine"/>
                    <module name="RedundantImport"/>
                    <module name="UnusedImports"/>
                </module>
            </module>
            """;

    @Override
    public void apply(Project root) {
        if (root != root.getRootProject()) {
            throw new GradleException(PLUGIN_ID + " must be applied to the root project.");
        }
        Provider<Boolean> strictQuality = root.getProviders()
                .gradleProperty("strictQuality")
                .map(Boolean::parseBoolean)
                .orElse(DEFAULT_STRICT_QUALITY);
        Provider<Boolean> strictUnchecked = root.getProviders()
                .gradleProperty("strictUnchecked")
                .map(Boolean::parseBoolean)
                .orElse(strictQuality.map(quality -> uncheckedDefault(quality)));
        root.allprojects(project -> project.getPluginManager().withPlugin(
                "java",
                applied -> configureJavaProject(project, strictQuality, strictUnchecked)
        ));
    }

    /** Whether the unchecked group is on when only {@code strictQuality} decides: with it, and with the group. */
    static boolean uncheckedDefault(boolean strictQuality) {
        return strictQuality && DEFAULT_STRICT_UNCHECKED;
    }

    private static void configureJavaProject(
            Project project, Provider<Boolean> strictQuality, Provider<Boolean> strictUnchecked) {
        project.getPluginManager().apply("com.diffplug.spotless");
        project.getPluginManager().apply("checkstyle");

        SpotlessExtension spotless = project.getExtensions().getByType(SpotlessExtension.class);
        spotless.java(java -> {
            java.target("src/**/*.java");
            java.googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION).aosp();
            java.removeUnusedImports();
            java.trimTrailingWhitespace();
            java.endWithNewline();
        });

        CheckstyleExtension checkstyle = project.getExtensions().getByType(CheckstyleExtension.class);
        checkstyle.setToolVersion(CHECKSTYLE_VERSION);
        checkstyle.setConfig(project.getResources().getText().fromString(CHECKSTYLE_CONFIG));
        checkstyle.setMaxWarnings(0);
        checkstyle.setIgnoreFailures(false);
        project.getTasks().withType(Checkstyle.class).configureEach(task ->
                task.onlyIf(spec -> strictQuality.get()));
        project.getTasks().matching(task ->
                        task.getName().startsWith("spotless") && task.getName().endsWith("Check"))
                .configureEach(task -> task.onlyIf(spec -> strictQuality.get()));

        List<String> lintArguments = lintArguments(strictQuality.get(), strictUnchecked.get());
        project.getTasks().withType(JavaCompile.class).configureEach(task ->
                task.getOptions().getCompilerArgs().addAll(lintArguments));
    }

    /**
     * The javac lint arguments of the two strict groups.
     *
     * <p>{@code -Werror} turns every enabled warning into an error, and javac cannot promote one
     * lint category alone, so the groups decide which lints are enabled: the deprecation group enables
     * {@code -Xlint:deprecation}, the unchecked group enables {@code -Xlint:unchecked} and
     * {@code -Xlint:rawtypes}, and only then does {@code -Werror} make them fail. With neither group
     * on, all three are warnings, which is where the fleet's counts come from.
     */
    static List<String> lintArguments(boolean strictQuality, boolean strictUnchecked) {
        List<String> arguments = new ArrayList<>();
        if (strictQuality || !strictUnchecked) {
            arguments.add("-Xlint:deprecation");
        }
        if (strictUnchecked || !strictQuality) {
            arguments.add("-Xlint:unchecked");
            arguments.add("-Xlint:rawtypes");
        }
        if (strictQuality || strictUnchecked) {
            arguments.add("-Werror");
        }
        return arguments;
    }
}
