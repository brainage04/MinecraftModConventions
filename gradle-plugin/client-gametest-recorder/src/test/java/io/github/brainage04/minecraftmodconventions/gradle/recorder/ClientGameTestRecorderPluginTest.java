package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientGameTestRecorderPluginTest {
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
}
