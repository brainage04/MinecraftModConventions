package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaTagIndexTest {
    private static final Map<String, String> VANILLA_TAGS = tags();

    @TempDir
    Path directory;

    private static Map<String, String> tags() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/minecraft/tags/block/beds.json",
                "{\"values\": [\"minecraft:red_bed\", \"minecraft:black_bed\"]}");
        entries.put("data/minecraft/tags/block/oak_logs.json", "{\"values\": [\"minecraft:oak_log\"]}");
        entries.put("data/minecraft/tags/block/logs.json", "{\"values\": [\"#minecraft:oak_logs\"]}");
        entries.put("data/minecraft/tags/block/mineable/pickaxe.json", "{\"values\": [\"minecraft:ice\"]}");
        entries.put("data/minecraft/tags/block/beacon_base_blocks.json",
                "{\"values\": [\"minecraft:red_bed\"]}");
        entries.put("data/minecraft/tags/blocks/wools.json", "{\"values\": [\"minecraft:white_wool\"]}");
        entries.put("data/minecraft/tags/item/swords.json", "{\"values\": [\"minecraft:diamond_sword\"]}");
        entries.put("data/minecraft/tags/worldgen/biome/is_forest.json", "{\"values\": [\"minecraft:forest\"]}");
        entries.put("data/minecraft/tags/block/other_namespace.json",
                "{\"values\": [{\"id\": \"othermod:thing\", \"required\": false}]}");
        return Map.copyOf(entries);
    }

    private VanillaTagIndex index() throws IOException {
        return TagDataFixture.index(directory, VANILLA_TAGS);
    }

    @Test
    void listsTheTagOfAnIdentityAndFollowsNestedTags() throws IOException {
        VanillaTagIndex index = index();
        assertEquals(List.of(new VanillaTagIndex.Member("block", "beds")), index.members("block", "red_bed"));
        assertEquals(
                List.of(new VanillaTagIndex.Member("block", "logs"), new VanillaTagIndex.Member("block", "oak_logs")),
                index.members("block", "oak_log"));
        assertEquals(List.of(new VanillaTagIndex.Member("item", "swords")), index.members("item", "diamond_sword"));
        assertEquals(List.of(new VanillaTagIndex.Member("biome", "is_forest")), index.members("biome", "forest"));
        assertEquals(List.of(new VanillaTagIndex.Member("block", "wools")), index.members("block", "white_wool"));
    }

    @Test
    void leavesOutToolTagsAndOtherNamespaces() throws IOException {
        VanillaTagIndex index = index();
        assertEquals(List.of(), index.members("block", "ice"));
        assertEquals(List.of(), index.members("block", "thing"));
        assertEquals(List.of(), index.members("block", "air"));
        assertEquals(List.of(), index.members("item", "red_bed"));
    }

    @Test
    void namesTheTagReferenceToReport() {
        assertEquals("block tag #minecraft:beds", VanillaTagIndex.reference(new VanillaTagIndex.Member("block", "beds")));
        assertEquals("item tag #minecraft:swords", VanillaTagIndex.reference(new VanillaTagIndex.Member("item", "swords")));
        assertEquals("biome tag #minecraft:is_forest",
                VanillaTagIndex.reference(new VanillaTagIndex.Member("biome", "is_forest")));
        assertEquals("item tag #minecraft:enchantable/durability",
                VanillaTagIndex.reference(new VanillaTagIndex.Member("item", "enchantable/durability")));
    }

    @Test
    void aJarWithoutTagDataGivesAnEmptyIndex() throws IOException {
        assertTrue(TagDataFixture.index(directory, Map.of("META-INF/MANIFEST.MF", "")).isEmpty());
    }
}
