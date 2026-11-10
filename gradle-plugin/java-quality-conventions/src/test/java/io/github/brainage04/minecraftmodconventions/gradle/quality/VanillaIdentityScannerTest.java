package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaIdentityScannerTest {
    private final VanillaIdentityScanner scanner = VanillaIdentityScanner.load();

    private List<Integer> hitLines(String source) {
        return scanner.scan(source.lines().toList()).stream().map(VanillaIdentityScanner.Finding::line).toList();
    }

    @Test
    void flagsTheAutoSleepOverworldGate() {
        assertEquals(List.of(2), hitLines("""
                // Beds explode in the Nether and the End.
                if (level.dimension() != Level.OVERWORLD) return;
                """));
    }

    @Test
    void flagsEveryComparisonShape() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), hitLines("""
                boolean a = level.dimension() == Level.NETHER;
                boolean b = Level.END != player.level().dimension();
                boolean c = level.dimension().equals(Level.END);
                boolean d = Level.NETHER.equals(level.dimension());
                boolean e = Objects.equals(level.dimension(), net.minecraft.world.level.Level.OVERWORLD);
                boolean f = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
                boolean g = key == ServerLevel.NETHER;
                boolean h = level.dimension() == NETHER;
                boolean i = OVERWORLD.equals(player.level().dimension());
                if (dimensionsDoNotMatch(source, Level.OVERWORLD, world.dimension())) return;
                boolean k = theme.dimension.equals(Level.NETHER);
                """));
    }

    @Test
    void passesDataDrivenCodeAndOtherUsesOfTheKeys() {
        assertEquals(List.of(), hitLines("""
                if (level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos).explodes()) return;
                boolean nether = !level.dimensionType().hasSkyLight() && level.dimensionType().hasCeiling();
                ServerLevel end = server.getLevel(Level.END);
                ResourceKey<Level> fallback = snapshot.read(KEY, Level.RESOURCE_KEY_CODEC).orElse(Level.OVERWORLD);
                boolean same = a.level().dimension() == b.level().dimension();
                boolean hub = level.dimension().equals(ModDimensions.HUB);
                boolean end2 = level.dimension() == ModDimensions.END;
                """));
    }

    @Test
    void ignoresCommentsAndStrings() {
        assertEquals(List.of(), hitLines("""
                // if (level.dimension() != Level.OVERWORLD) return;
                /* level.dimension() == Level.NETHER
                   still a comment: Level.END.equals(level.dimension()) */
                String message = "level.dimension() == Level.NETHER";
                char quote = '"'; String other = "Level.END == x";
                """));
    }

    @Test
    void optOutWithAReasonOnTheLineOrTheLineAbove() {
        assertEquals(List.of(), hitLines("""
                if (level.dimension() != Level.END) return; // vanilla-identity: this mod floods the vanilla End only
                // vanilla-identity: BED_RULE is not synced to the client; fall back to the vanilla dimensions
                if (level.dimension() != Level.OVERWORLD) return;
                """));
    }

    @Test
    void optOutWithoutAReasonStillWarns() {
        List<VanillaIdentityScanner.Finding> findings = scanner.scan(List.of(
                "// vanilla-identity:",
                "if (level.dimension() != Level.OVERWORLD) return;",
                "if (level.dimension() != Level.NETHER) return;"));
        assertEquals(List.of(
                new VanillaIdentityScanner.Finding(2, "if (level.dimension() != Level.OVERWORLD) return;", true),
                new VanillaIdentityScanner.Finding(3, "if (level.dimension() != Level.NETHER) return;", false)
        ), findings);
    }
}
