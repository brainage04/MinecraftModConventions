package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.testing.Test;

/** Ordinary tests never launch a server; expensive campaigns are explicit tasks. */
final class QualityTesting {
    private QualityTesting() {}

    static void configure(Project project) {
        var dependencies = project.getDependencies();
        dependencies.add("testImplementation", dependencies.platform("org.junit:junit-bom:5.14.4"));
        dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter");
        dependencies.add("testImplementation", "net.jqwik:jqwik:1.10.2");
        dependencies.add("testImplementation", "com.code-intelligence:jazzer-junit:0.30.0");
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher");
        project.getTasks()
                .withType(Test.class)
                .configureEach(
                        task -> {
                            task.useJUnitPlatform();
                            task.setMaxHeapSize("1G");
                            task.environment("JAZZER_FUZZ", "");
                            task.systemProperty(
                                    "jqwik.database",
                                    project.getLayout()
                                            .getBuildDirectory()
                                            .file("jqwik-database")
                                            .get()
                                            .getAsFile()
                                            .toString());
                        });
        var sources = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        SourceSet main = sources.getByName("main");
        SourceSet tests = sources.getByName("test");
        project.getTasks()
                .register(
                        "jazzerFuzz",
                        Test.class,
                        task -> {
                            task.setDescription(
                                    "Bounded Jazzer campaign; test runs saved regression inputs only.");
                            task.setGroup("verification");
                            task.setTestClassesDirs(tests.getOutput().getClassesDirs());
                            task.setClasspath(tests.getRuntimeClasspath());
                            task.include("**/*FuzzTest.class");
                            task.onlyIf(
                                    ignored ->
                                            !tests.getAllJava()
                                                    .matching(
                                                            pattern ->
                                                                    pattern.include(
                                                                            "**/*FuzzTest.java"))
                                                    .isEmpty());
                            task.environment("JAZZER_FUZZ", "1");
                            task.getOutputs().upToDateWhen(ignored -> false);
                            task.setForkEvery(1L);
                        });
        Configuration pit = tool(project, "qualityPit", "org.pitest:pitest-command-line:1.30.1");
        dependencies.add(pit.getName(), "org.pitest:pitest-junit5-plugin:1.2.3");
        // Pure-policy mutation uses PIT's instrumentation, not Fabric's conflicting Knot launcher.
        // Keep the Minecraft API/dependencies; omit only the JUnit session hook that boots Knot.
        var mutationClasspath = tests.getRuntimeClasspath()
                .filter(file -> !file.getName().startsWith("fabric-loader-junit-"));
        project.getTasks()
                .register(
                        "pitest",
                        JavaExec.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Mutate explicitly selected pure policies, never GameTests.");
                            task.dependsOn("testClasses");
                            var mutationTargets =
                                    project.getProviders()
                                            .gradleProperty(
                                                    "qualityMutationTargets"
                                                            + (project == project.getRootProject()
                                                                    ? ""
                                                                    : "." + project.getName()));
                            task.setClasspath(pit);
                            task.getMainClass()
                                    .set(
                                            "org.pitest.mutationtest.commandline.MutationCoverageReport");
                            task.setMaxHeapSize("1G");
                            task.onlyIf(ignored -> mutationTargets.isPresent());
                            task.doFirst(
                                    ignored ->
                                            task.setArgs(
                                                    List.of(
                                                            "--reportDir",
                                                            project.getLayout()
                                                                    .getBuildDirectory()
                                                                    .dir("reports/pitest")
                                                                    .get()
                                                                    .getAsFile()
                                                                    .toString(),
                                                            "--targetClasses",
                                                            mutationTargets.get(),
                                                            "--targetTests",
                                                            "*Properties,*Test",
                                                            "--excludedTestClasses",
                                                            "*FuzzTest",
                                                            "--sourceDirs",
                                                            String.join(
                                                                    ",",
                                                                    main
                                                                            .getAllJava()
                                                                            .getSourceDirectories()
                                                                            .getFiles()
                                                                            .stream()
                                                                            .map(
                                                                                    java.io.File
                                                                                            ::toString)
                                                                            .toList()),
                                                            "--classPath",
                                                            String.join(
                                                                    ",",
                                                                    mutationClasspath
                                                                            .getFiles()
                                                                            .stream()
                                                                            .map(
                                                                                    java.io.File
                                                                                            ::toString)
                                                                            .toList()),
                                                            "--outputFormats",
                                                            "XML,HTML",
                                                            "--threads",
                                                            "1",
                                                            "--timeoutConst",
                                                            "5000",
                                                            "--jvmArgs",
                                                            "-Xmx1G,-Djqwik.database="
                                                                    + project.getLayout()
                                                                            .getBuildDirectory()
                                                                            .file("pitest-jqwik-database")
                                                                            .get()
                                                                            .getAsFile())));
                        });
        Configuration spotbugs =
                tool(project, "qualitySpotbugs", "com.github.spotbugs:spotbugs:4.10.5");
        project.getTasks()
                .register(
                        "spotbugsReport",
                        JavaExec.class,
                        task -> {
                            task.setGroup("verification");
                            task.dependsOn("classes");
                            task.setClasspath(spotbugs);
                            task.getMainClass().set("edu.umd.cs.findbugs.LaunchAppropriateUI");
                            task.setMaxHeapSize("1G");
                            task.onlyIf(
                                    ignored ->
                                            !main.getOutput()
                                                    .getClassesDirs()
                                                    .getAsFileTree()
                                                    .isEmpty());
                            task.doFirst(
                                    ignored -> {
                                        var output =
                                                project.getLayout()
                                                        .getBuildDirectory()
                                                        .file("reports/spotbugs.xml")
                                                        .get()
                                                        .getAsFile();
                                        output.getParentFile().mkdirs();
                                        task.setArgs(
                                                List.of(
                                                        "-textui",
                                                        "-xml:withMessages",
                                                        "-output",
                                                        output.toString(),
                                                        "-auxclasspath",
                                                        main.getCompileClasspath().getAsPath(),
                                                        main.getOutput()
                                                                .getClassesDirs()
                                                                .getAsPath()));
                                    });
                        });
        Configuration pmd = tool(project, "qualityPmd", "net.sourceforge.pmd:pmd-cli:7.28.0");
        dependencies.add(pmd.getName(), "net.sourceforge.pmd:pmd-java:7.28.0");
        for (String mode : List.of("check", "cpd")) {
            project.getTasks()
                    .register(
                            mode.equals("check") ? "pmdReport" : "cpdReport",
                            JavaExec.class,
                            task -> {
                                task.setGroup("verification");
                                task.setClasspath(pmd);
                                task.getMainClass().set("net.sourceforge.pmd.cli.PmdCli");
                                task.setMaxHeapSize("1G");
                                task.onlyIf(ignored -> !main.getAllJava().isEmpty());
                                task.doFirst(
                                        ignored -> {
                                            var output =
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .file("reports/" + mode + ".xml")
                                                            .get()
                                                            .getAsFile();
                                            output.getParentFile().mkdirs();
                                            task.args(
                                                    mode,
                                                    "--dir",
                                                    project.file("src/main/java"),
                                                    "--format",
                                                    "xml",
                                                    "--report-file",
                                                    output,
                                                    "--no-fail-on-violation");
                                            if (mode.equals("check")) {
                                                task.args(
                                                        "--use-version",
                                                        "java-25",
                                                        "--rulesets",
                                                        "category/java/bestpractices.xml/UnusedPrivateField,category/java/bestpractices.xml/UnusedPrivateMethod,category/java/bestpractices.xml/UnusedAssignment",
                                                        "--aux-classpath",
                                                        main.getCompileClasspath().getAsPath());
                                            } else {
                                                task.args(
                                                        "--language",
                                                        "java",
                                                        "--minimum-tokens",
                                                        "100");
                                            }
                                        });
                            });
        }
    }

    private static Configuration tool(Project project, String name, String dependency) {
        Configuration configuration = project.getConfigurations().create(name);
        configuration.setCanBeConsumed(false);
        project.getDependencies().add(name, dependency);
        return configuration;
    }
}
