package io.github.brainage04.minecraftmodconventions.gradle.quality;

import io.github.brainage04.minecraftmodconventions.gradle.quality.StructuralVanillaScanner.Check;
import io.github.brainage04.minecraftmodconventions.gradle.quality.StructuralVanillaScanner.Finding;
import io.github.brainage04.minecraftmodconventions.gradle.quality.StructuralVanillaScanner.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralVanillaScannerTest {
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

    private List<Finding> findings(Check check, String source) throws IOException {
        VanillaTagIndex index = TagDataFixture.index(directory, VANILLA_TAGS);
        return StructuralVanillaScanner.scan(List.of(new Source("Test.java", source)), index).stream()
                .filter(finding -> finding.check() == check)
                .toList();
    }

    private List<Integer> lines(Check check, String source) throws IOException {
        return findings(check, source).stream().map(Finding::line).toList();
    }

    @Test
    void checksEveryComparisonShape() throws IOException {
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), lines(Check.IDENTITY, """
                class Test {
                    void test(Level level, Player player, Object source, Object key, Theme theme, Region a, Region b) {
                        boolean one = level.dimension() == Level.NETHER;
                        boolean two = Level.END != player.level().dimension();
                        boolean three = level.dimension().equals(Level.END);
                        boolean four = Level.NETHER.equals(level.dimension());
                        boolean five = Objects.equals(level.dimension(), net.minecraft.world.level.Level.OVERWORLD);
                        boolean six = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
                        boolean seven = key == ServerLevel.NETHER;
                        boolean eight = level.dimension() == NETHER;
                        if (dimensionsDoNotMatch(source, Level.OVERWORLD, world.dimension())) return;
                        boolean nine = theme.dimension.equals(Level.NETHER);
                    }
                }
                """));
    }

    @Test
    void passesDataDrivenCodeAndOtherUsesOfTheKeys() throws IOException {
        assertEquals(List.of(), lines(Check.IDENTITY, """
                class Test {
                    void test(Level level, ServerLevel server, BlockPos pos, Snapshot snapshot, Region a, Region b) {
                        if (level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos).explodes()) return;
                        boolean nether = !level.dimensionType().hasSkyLight();
                        ServerLevel end = server.getLevel(Level.END);
                        ResourceKey<Level> fallback = snapshot.read(KEY, Level.RESOURCE_KEY_CODEC).orElse(Level.OVERWORLD);
                        boolean same = a.level().dimension() == b.level().dimension();
                        boolean hub = level.dimension().equals(ModDimensions.HUB);
                        boolean mod = level.dimension() == ModDimensions.END;
                    }
                }
                """));
    }

    @Test
    void ignoresCommentsAndStrings() throws IOException {
        assertEquals(List.of(), lines(Check.IDENTITY, """
                class Test {
                    void test(Level level) {
                        // if (level.dimension() != Level.OVERWORLD) return;
                        /* level.dimension() == Level.NETHER
                           still a comment: Level.END.equals(level.dimension()) */
                        String message = "level.dimension() == Level.NETHER";
                        char quote = '"'; String other = "Level.END == x";
                    }
                }
                """));
    }

    @Test
    void checksAComparisonOfAnIdentityATagLists() throws IOException {
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 9, 10), lines(Check.TAG, """
                class Test {
                    void test(BlockState state, ItemStack stack, Block block, ResourceKey<Biome> biomeKey) {
                        boolean one = block == Blocks.RED_BED;
                        if (block != Blocks.BLACK_BED) return;
                        if (state.is(Blocks.RED_BED)) return;
                        boolean two = stack.is(Items.DIAMOND_SWORD);
                        boolean three = Blocks.OAK_LOG.equals(block);
                        boolean four = Objects.equals(state.getBlock(), Blocks.RED_BED);
                        boolean five = biomeKey.equals(Biomes.FOREST);
                        boolean six = block == net.minecraft.world.level.block.Blocks.RED_BED;
                    }
                }
                """));
    }

    @Test
    void checksMembershipLists() throws IOException {
        List<Finding> findings = findings(Check.TAG, """
                class Test {
                    void test() {
                        Set<Block> beds = Set.of(Blocks.RED_BED, Blocks.BLACK_BED);
                        List<Item> weapons = List.of(Items.DIAMOND_SWORD);
                        Set<Block> tail = Set.of(Blocks.STONE, Blocks.RED_BED);
                        Set<Block> head = Set.of(Blocks.RED_BED, Blocks.STONE);
                    }
                }
                """);
        assertEquals(List.of(3, 4, 5, 6), findings.stream().map(Finding::line).toList());
        assertEquals(List.of("block tag #minecraft:beds"), findings.get(0).tags());
        assertEquals(List.of("item tag #minecraft:swords"), findings.get(1).tags());
    }

    /** The regular expression scans a line at a time, so this shape is invisible to it; a syntax tree sees it. */
    @Test
    void seesAMembershipListThatSpansLines() throws IOException {
        List<Finding> findings = findings(Check.TAG, """
                class Test {
                    void test() {
                        Set<Block> beds =
                                Set.of(
                                        Blocks.RED_BED,
                                        Blocks.BLACK_BED);
                    }
                }
                """);
        assertEquals(List.of(4), findings.stream().map(Finding::line).toList());
        assertEquals(List.of("block tag #minecraft:beds"), findings.get(0).tags());
    }

    @Test
    void passesPlacingCodeAndIdentitiesTheTagDataOmits() throws IOException {
        assertEquals(List.of(), lines(Check.TAG, """
                class Test {
                    void test(BlockState state, ItemStack stack, Level level, BlockPos pos, Player player) {
                        level.setBlock(pos, Blocks.RED_BED.defaultBlockState(), 3);
                        player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
                        Stream.of(new ItemStack(Items.ARROW)).forEach(menu::add);
                        List.of(new ItemStack(Items.DIAMOND_SWORD)).forEach(menu::add);
                        if (state.is(Blocks.ICE)) return;
                        if (state.is(Blocks.AIR)) return;
                        if (state.is(ModBlocks.BED)) return;
                    }
                }
                """));
    }

    @Test
    void checksTheOverworldLimits() throws IOException {
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 9, 10, 11), lines(Check.HEIGHT, """
                class Test {
                    void test(int y, int x, int z, Level level, BlockPos pos, int width, int distance) {
                        if (y < -64) return;
                        if (level.getY() > 320) return;
                        int maxY = 320;
                        if (pos.getY() >= 319) return;
                        BlockPos top = new BlockPos(x, 320, z);
                        int minY = 0;
                        int worldHeight = 256;
                        if (320 > y) return;
                        int clamped = Mth.clamp(y, -64, 320);
                        if (y < 0) return;
                        if (width == 256) return;
                        if (distance == 319) return;
                    }
                }
                """));
    }

    @Test
    void aMarkedLineIsSuppressed() throws IOException {
        String source = """
                class Test {
                    void test(Level level) {
                        if (level.dimension() != Level.END) return; // vanilla-identity: the mod floods the vanilla End only
                        // vanilla-identity: BED_RULE is not synced to the client
                        if (level.dimension() != Level.OVERWORLD) return;
                    }
                }
                """;
        List<Finding> findings = findings(Check.IDENTITY, source);
        assertTrue(StructuralVanillaScanner.suppressed(Check.IDENTITY, source.lines().toList(), findings.get(0).line()));
        assertTrue(StructuralVanillaScanner.suppressed(Check.IDENTITY, source.lines().toList(), findings.get(1).line()));
        assertFalse(StructuralVanillaScanner.suppressed(Check.IDENTITY, List.of("if (x)"), 1));
    }
}
