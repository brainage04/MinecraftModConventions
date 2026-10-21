package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.gradle.api.Project;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.provider.Provider;

/** New rules report first; numeric-literal noise stays outside enforcement. */
final class CheckstyleReports {
    private static final String HEADER =
            """
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
                <property name="charset" value="UTF-8"/>
                <module name="TreeWalker">
            """;
    private static final String FOOTER = "</module></module>";
    private static final String ADDITIONAL =
            HEADER
                    + """
                    <module name="MissingSwitchDefault"/>
                    <module name="EmptyCatchBlock"/>
                    <module name="StringLiteralEquality"/>
                    <module name="ModifiedControlVariable"/>
                    <module name="InnerAssignment"/>
                    <module name="MultipleVariableDeclarations"/>
                    <module name="NeedBraces"/>
                    <module name="MissingOverride"/>
                    <module name="UpperEll"/>
                    <module name="OneTopLevelClass"/>
                    <module name="HideUtilityClassConstructor"/>
                    <module name="IllegalImport">
                        <property name="illegalPkgs" value="sun,jdk.internal,com.sun.org"/>
                    </module>
            """
                    + FOOTER;
    private static final String MAGIC_NUMBERS =
            HEADER
                    + """
                    <module name="MagicNumber">
                        <property name="ignoreHashCodeMethod" value="true"/>
                        <property name="ignoreAnnotation" value="true"/>
                        <property name="ignoreAnnotationElementDefaults" value="true"/>
                        <property name="ignoreNumbers" value="-1,0,1,2"/>
                    </module>
            """
                    + FOOTER;
    private static final String IMPORT_CHECK =
            HEADER
                    + """
                    <module name="ImportControl">
                        <property name="file" value="${qualityImportControl}"/>
                    </module>
            """
                    + FOOTER;
    private static final String IMPORT_RULES =
            """
            <?xml version="1.0"?>
            <!DOCTYPE import-control PUBLIC "-//Checkstyle//DTD ImportControl Configuration 1.4//EN"
                "https://checkstyle.org/dtds/import_control_1_4.dtd">
            <import-control pkg=".*" regex="true" strategyOnMismatch="allowed">
                <disallow pkg="net.minecraft.client"/>
                <disallow pkg="com.mojang.blaze3d"/>
                <disallow pkg="net.fabricmc.fabric.api.client"/>
                <disallow pkg="net.neoforged.neoforge.client"/>
                <disallow pkg="org.lwjgl"/>
            </import-control>
            """;

    private CheckstyleReports() {}

    static void configure(Project project) {
        Provider<Boolean> enforce =
                project.getProviders()
                        .gradleProperty("qualityCheckstyleAdditionalEnforce")
                        .map(Boolean::parseBoolean)
                        .orElse(false);
        FileTree allJava = project.fileTree("src", files -> files.include("**/*.java"));
        FileTree mainJava = project.fileTree("src/main/java", files -> files.include("**/*.java"));
        String side =
                project.getRootProject().getProviders().gradleProperty("mod_side").getOrElse("");
        boolean commonOrServer =
                project.getName().equals("common")
                        || project.getName().equals("protocol")
                        || side.equals("server")
                        || side.equals("both");
        FileTree serverJava =
                commonOrServer
                        ? mainJava
                        : mainJava.matching(
                                files ->
                                        files.include(
                                                "**/server/**/*.java", "**/common/**/*.java"));

        register(project, "checkstyleAdditional", ADDITIONAL, allJava, enforce, true);
        register(project, "checkstyleMagicNumbers", MAGIC_NUMBERS, mainJava, enforce, false);
        register(project, "checkstyleImports", IMPORT_CHECK, serverJava, enforce, true);
        project.getTasks()
                .named("checkstyleImports", Checkstyle.class)
                .configure(
                        task -> {
                            var rules =
                                    project.getResources()
                                            .getText()
                                            .fromString(IMPORT_RULES)
                                            .asFile();
                            task.getConfigProperties()
                                    .put("qualityImportControl", rules.getAbsolutePath());
                            task.getInputs().file(rules);
                        });
        project.getPluginManager()
                .withPlugin(
                        "java-gradle-plugin",
                        applied ->
                                project.getTasks()
                                        .named("checkstyleImports", Checkstyle.class)
                                        .configure(task -> task.setSource(mainJava)));
        project.getTasks()
                .named("check")
                .configure(
                        task ->
                                task.dependsOn(
                                        "checkstyleAdditional",
                                        "checkstyleMagicNumbers",
                                        "checkstyleImports"));
    }

    private static void register(
            Project project,
            String name,
            String configuration,
            FileTree source,
            Provider<Boolean> enforce,
            boolean canEnforce) {
        project.getTasks()
                .register(
                        name,
                        Checkstyle.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Staged Checkstyle report; MagicNumber is always report-only.");
                            task.setSource(source);
                            task.setConfig(
                                    project.getResources().getText().fromString(configuration));
                            task.setIgnoreFailures(!canEnforce || !enforce.get());
                            task.getInputs()
                                    .property("qualityEnforce", canEnforce && enforce.get());
                            task.setShowViolations(false);
                            task.getReports().getXml().getRequired().set(true);
                            task.getReports()
                                    .getXml()
                                    .getOutputLocation()
                                    .set(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .file("reports/checkstyle/" + name + ".xml"));
                            task.getReports().getHtml().getRequired().set(true);
                            task.getReports()
                                    .getHtml()
                                    .getOutputLocation()
                                    .set(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .file("reports/checkstyle/" + name + ".html"));
                        });
    }
}
