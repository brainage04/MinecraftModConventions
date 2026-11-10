package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds the vanilla tag index a test needs from a jar of tag JSONs, without a Minecraft download. */
final class TagDataFixture {
    private TagDataFixture() {
    }

    static VanillaTagIndex index(Path directory, Map<String, String> entries) throws IOException {
        Path jar = directory.resolve("minecraft-tags.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return VanillaTagIndex.load(List.of(jar.toFile()));
    }
}
