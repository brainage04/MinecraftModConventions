package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaTagIdentityScannerTest {
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
        entries.put("data/minecraft/tags/item/swords.json", "{\"values\": [\"minecraft:diamond_sword\"]}");
        entries.put("data/minecraft/tags/worldgen/biome/is_forest.json", "{\"values\": [\"minecraft:forest\"]}");
        return Map.copyOf(entries);
    }

    private List<VanillaTagIdentityScanner.Finding> findings(String source) throws IOException {
        return VanillaTagIdentityScanner.load(TagDataFixture.index(directory, VANILLA_TAGS))
                .scan(source.lines().toList());
    }

    private List<Integer> hitLines(String source) throws IOException {
        return findings(source).stream().map(VanillaTagIdentityScanner.Finding::line).toList();
    }

    @Test
    void flagsAComparisonOfAnIdentityATagLists() throws IOException {
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), hitLines("""
                boolean bed = block == Blocks.RED_BED;
                if (block != Blocks.BLACK_BED) return;
                if (state.is(Blocks.RED_BED)) return;
                boolean sword = stack.is(Items.DIAMOND_SWORD);
                boolean log = Blocks.OAK_LOG.equals(block);
                boolean same = Objects.equals(state.getBlock(), Blocks.RED_BED);
                boolean forest = biomeKey.equals(Biomes.FOREST);
                boolean qualified = block == net.minecraft.world.level.block.Blocks.RED_BED;
                """));
    }

    @Test
    void flagsMembershipLists() throws IOException {
        List<VanillaTagIdentityScanner.Finding> findings = findings("""
                Set<Block> beds = Set.of(Blocks.RED_BED, Blocks.BLACK_BED);
                List<Item> weapons = List.of(Items.DIAMOND_SWORD);
                Set<Block> tail = Set.of(Blocks.STONE, Blocks.RED_BED);
                Set<Block> head = Set.of(Blocks.RED_BED, Blocks.STONE);
                """);
        assertEquals(List.of(1, 2, 3, 4), findings.stream().map(VanillaTagIdentityScanner.Finding::line).toList());
        assertEquals(List.of("block tag #minecraft:beds"), findings.get(0).tags());
        assertEquals(List.of("item tag #minecraft:swords"), findings.get(1).tags());
        assertEquals(List.of("block tag #minecraft:beds"), findings.get(2).tags());
        assertEquals(List.of("block tag #minecraft:beds"), findings.get(3).tags());
    }

    @Test
    void passesPlacingCodeGivingCodeAndIdentitiesTheTagDataOmits() throws IOException {
        assertEquals(List.of(), hitLines("""
                level.setBlock(pos, Blocks.RED_BED.defaultBlockState(), 3);
                player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
                Stream.of(new ItemStack(Items.ARROW, Items.ARROW.getDefaultMaxStackSize())).forEach(menu::add);
                List.of(new ItemStack(Items.DIAMOND_SWORD)).forEach(menu::add);
                if (block == Blocks.ICE) return;
                if (block == Blocks.AIR) return;
                if (block == ModBlocks.BED) return;
                """));
    }

    @Test
    void ignoresCommentsAndStrings() throws IOException {
        assertEquals(List.of(), hitLines("""
                // block == Blocks.RED_BED
                /* block == Blocks.RED_BED */
                String message = "block == Blocks.RED_BED";
                """));
    }

    @Test
    void optOutWithAReasonOnTheLineOrTheLineAbove() throws IOException {
        assertEquals(List.of(), hitLines("""
                if (block == Blocks.RED_BED) return; // vanilla-tag: this mod is about beds by name
                // vanilla-tag: the mod replaces the bed block only
                if (block == Blocks.RED_BED) return;
                """));
    }

    @Test
    void optOutWithoutAReasonStillWarns() throws IOException {
        List<VanillaTagIdentityScanner.Finding> findings = findings("""
                // vanilla-tag:
                if (block == Blocks.RED_BED) return;
                if (block == Blocks.BLACK_BED) return;
                """);
        assertEquals(List.of(
                new VanillaTagIdentityScanner.Finding(
                        2, "if (block == Blocks.RED_BED) return;", true, List.of("block tag #minecraft:beds")),
                new VanillaTagIdentityScanner.Finding(
                        3, "if (block == Blocks.BLACK_BED) return;", false, List.of("block tag #minecraft:beds"))
        ), findings);
    }
}
