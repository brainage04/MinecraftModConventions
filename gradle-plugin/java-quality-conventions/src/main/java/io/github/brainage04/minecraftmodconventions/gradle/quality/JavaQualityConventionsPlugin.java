package io.github.brainage04.minecraftmodconventions.gradle.quality;

import com.diffplug.gradle.spotless.SpotlessExtension;
import net.ltgt.gradle.errorprone.ErrorProneOptions;
import org.gradle.api.plugins.ExtensionAware;
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
        Provider<Boolean> formatting = enabled(root, "qualityFormatting", true);
        Provider<Boolean> checkstyle = enabled(root, "qualityCheckstyle", true);
        Provider<Boolean> errorProne = enabled(root, "qualityErrorProne", true);
        Provider<Boolean> werror = enabled(root, "qualityWerror", false);
        var nightly = root.getTasks().register("qualityNightly", task -> {
            task.setGroup("verification");
            task.setDescription("Policy mutation, bounded fuzzing and report-only analyzers; no server runs.");
        });
        root.allprojects(project -> project.getPluginManager().withPlugin(
                "java",
                applied -> {
                    configureJavaProject(project, formatting, checkstyle, errorProne, werror);
                    nightly.configure(task -> task.dependsOn(
                            project.getTasks().named("test"), project.getTasks().named("pitest"),
                            project.getTasks().named("jazzerFuzz"), project.getTasks().named("spotbugsReport"),
                            project.getTasks().named("pmdReport"), project.getTasks().named("cpdReport")));
                }
        ));
    }

    private static Provider<Boolean> enabled(Project project, String name, boolean fallback) {
        return project.getProviders().gradleProperty(name).map(Boolean::parseBoolean).orElse(fallback);
    }

    private static void configureJavaProject(
            Project project, Provider<Boolean> formatting, Provider<Boolean> checkstyleEnabled,
            Provider<Boolean> errorProne, Provider<Boolean> werror) {
        project.getPluginManager().apply(com.diffplug.gradle.spotless.SpotlessPlugin.class);
        project.getPluginManager().apply("checkstyle");
        project.getPluginManager().apply(net.ltgt.gradle.errorprone.ErrorPronePlugin.class);
        project.getDependencies().add("errorprone", "com.google.errorprone:error_prone_core:2.50.0");
        project.getDependencies().add("errorprone", "com.uber.nullaway:nullaway:0.14.2");
        project.getDependencies().add("compileOnly", "org.jspecify:jspecify:1.0.0");
        QualityTesting.configure(project);

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
        project.getTasks().withType(Checkstyle.class).configureEach(task -> {
            // These seven syntax rules do not need compilation or a Minecraft classpath.
            task.setClasspath(project.files());
            // The shared GameTest source trees exceed Checkstyle's default worker heap.
            task.getMaxHeapSize().set("2G");
            task.onlyIf(spec -> checkstyleEnabled.get());
        });
        project.getTasks().matching(task ->
                        task.getName().startsWith("spotless") && task.getName().endsWith("Check"))
                .configureEach(task -> task.onlyIf(spec -> formatting.get()));

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getCompilerArgs().addAll(List.of(
                    "-Xlint:deprecation",
                    "-Xlint:unchecked",
                    "-Xlint:rawtypes",
                    "-Xmaxwarns", "10000"
            ));
            ErrorProneOptions analysis = ((ExtensionAware) task.getOptions())
                    .getExtensions().getByType(ErrorProneOptions.class);
            analysis.getEnabled().set(errorProne.map(value -> value && task.getName().equals("compileJava")));
            analysis.getDisableAllChecks().set(true);
            analysis.warn("CollectionIncompatibleType", "EqualsIncompatibleType", "ReferenceEquality",
                    "ReturnValueIgnored", "CheckReturnValue", "DeadException");
            analysis.error("NullAway");
            analysis.option("NullAway:OnlyNullMarked", "true");
            if (werror.get()) {
                task.getOptions().getCompilerArgs().add("-Werror");
            }
        });
    }
}
