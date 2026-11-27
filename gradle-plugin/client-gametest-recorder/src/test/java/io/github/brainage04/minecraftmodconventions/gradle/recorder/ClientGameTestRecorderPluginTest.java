package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestRecorderPluginTest {
    @Test
    void aSwitchAcceptsTheSpellingsTheOtherSwitchesDo() {
        for (String on : new String[] {"1", "true", "TRUE", "yes", "on", " true"}) {
            assertTrue(ClientGameTestRecorderPlugin.truthy(on), on);
        }
        for (String off : new String[] {"0", "false", "no", "off", "", null}) {
            assertFalse(ClientGameTestRecorderPlugin.truthy(off), String.valueOf(off));
        }
    }

    @Test
    void rootProjectUsesUnqualifiedRunTaskName() {
        assertEquals("runClientGameTest", ClientGameTestRecorderPlugin.clientGameTestTaskPath(":"));
    }

    @Test
    void subprojectUsesQualifiedRunTaskPath() {
        assertEquals(":fabric:runClientGameTest", ClientGameTestRecorderPlugin.clientGameTestTaskPath(":fabric"));
    }

    @Test
    void subprojectRunDirectoryIsRelativeToTheSubproject(@TempDir Path temporaryDirectory) throws Exception {
        Path rootDirectory = Files.createDirectory(temporaryDirectory.resolve("mod"));
        Project rootProject = ProjectBuilder.builder().withProjectDir(rootDirectory.toFile()).build();
        Path projectDirectory = Files.createDirectory(rootDirectory.resolve("fabric"));
        Project subproject = ProjectBuilder.builder()
                .withName("fabric")
                .withProjectDir(projectDirectory.toFile())
                .withParent(rootProject)
                .build();

        assertEquals(
                "build/run/clientGameTest",
                ClientGameTestRecorderPlugin.clientGameTestRunDirectory(
                        subproject,
                        projectDirectory.resolve("build/run/clientGameTest").toFile()
                )
        );
    }

    @Test
    void theInputOverlayPropertyWinsOverTheBuildScriptSetting(@TempDir Path temporaryDirectory) throws Exception {
        Files.writeString(temporaryDirectory.resolve("gradle.properties"), "inputOverlay=false\n");
        Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
        ClientGameTestRecorderExtension extension =
                project.getObjects().newInstance(ClientGameTestRecorderExtension.class);
        extension.getInputOverlay().set(true);

        assertFalse(extension.effectiveInputOverlay().get());
    }

    @Test
    void withoutTheInputOverlayPropertyTheBuildScriptSettingDecides(@TempDir Path temporaryDirectory) {
        Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
        ClientGameTestRecorderExtension extension =
                project.getObjects().newInstance(ClientGameTestRecorderExtension.class);
        assertTrue(extension.effectiveInputOverlay().get());

        extension.getInputOverlay().set(false);

        assertFalse(extension.effectiveInputOverlay().get());
    }
}
