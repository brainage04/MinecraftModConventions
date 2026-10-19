package io.github.brainage04.minecraftmodconventions.gradle.quality;

import com.diffplug.gradle.spotless.SpotlessExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.util.List;

/** Configures one staged Java quality policy across every Java project in a build. */
public final class JavaQualityConventionsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.java-quality-conventions";

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
                .orElse(false);
        root.allprojects(project -> project.getPluginManager().withPlugin(
                "java",
                applied -> configureJavaProject(project, strictQuality)
        ));
    }

    private static void configureJavaProject(Project project, Provider<Boolean> strictQuality) {
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

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getCompilerArgs().addAll(List.of(
                    "-Xlint:deprecation",
                    "-Xlint:unchecked",
                    "-Xlint:rawtypes"
            ));
            if (strictQuality.get()) {
                task.getOptions().getCompilerArgs().add("-Werror");
            }
        });
    }
}
