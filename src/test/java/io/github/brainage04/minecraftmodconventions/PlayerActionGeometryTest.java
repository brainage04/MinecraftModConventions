package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerActionGeometryTest {
    private static final double DELTA = 1.0e-6D;

    // Look direction: the yaw and pitch the player turns to, in Minecraft's units.

    @Test
    void yawFacesTheFourCardinalDirections() {
        // 0 is south (+z), 90 west (-x), 180 north (-z) and -90 east (+x).
        assertEquals(0.0D, PlayerActionGeometry.yawTo(0.0D, 5.0D), DELTA);
        assertEquals(90.0D, PlayerActionGeometry.yawTo(-5.0D, 0.0D), DELTA);
        assertEquals(180.0D, PlayerActionGeometry.yawTo(0.0D, -5.0D), DELTA);
        assertEquals(-90.0D, PlayerActionGeometry.yawTo(5.0D, 0.0D), DELTA);
    }

    @Test
    void yawStaysInTheRangeTheGameAccepts() {
        for (double yaw : new double[] {-180.0D, -179.9D, -1.0D, 0.0D, 1.0D, 179.9D, 180.0D}) {
            double radians = Math.toRadians(yaw + 90.0D);
            double dx = Math.cos(radians);
            double dz = Math.sin(radians);
            double turned = PlayerActionGeometry.yawTo(dx, dz);
            assertTrue(turned > -180.0D && turned <= 180.0D, "yaw " + turned + " is outside (-180, 180]");
            // The forward vector of a yaw is (-sin, cos); the point must lie on it.
            double forwardX = -Math.sin(Math.toRadians(turned));
            double forwardZ = Math.cos(Math.toRadians(turned));
            assertEquals(dx, forwardX, 1.0e-9D, "x of yaw " + turned);
            assertEquals(dz, forwardZ, 1.0e-9D, "z of yaw " + turned);
        }
    }

    @Test
    void pitchLooksUpForAPointAboveAndDownForOneBelow() {
        assertEquals(-45.0D, PlayerActionGeometry.pitchTo(5.0D, 5.0D, 0.0D), DELTA);
        assertEquals(45.0D, PlayerActionGeometry.pitchTo(-5.0D, 0.0D, 5.0D), DELTA);
        assertEquals(0.0D, PlayerActionGeometry.pitchTo(0.0D, 5.0D, 5.0D), DELTA);
        assertEquals(-90.0D, PlayerActionGeometry.pitchTo(5.0D, 0.0D, 0.0D), DELTA);
        assertEquals(90.0D, PlayerActionGeometry.pitchTo(-5.0D, 0.0D, 0.0D), DELTA);
    }

    @Test
    void theTurnsRoundTripToThePointTheyAimedAt() {
        double dx = 3.0D;
        double dy = -2.0D;
        double dz = -4.0D;
        double yaw = PlayerActionGeometry.yawTo(dx, dz);
        double pitch = PlayerActionGeometry.pitchTo(dy, dx, dz);
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Minecraft's view vector for a yaw and pitch.
        double x = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double y = -Math.sin(pitchRadians);
        double z = Math.cos(yawRadians) * Math.cos(pitchRadians);
        assertEquals(dx / length, x, 1.0e-9D);
        assertEquals(dy / length, y, 1.0e-9D);
        assertEquals(dz / length, z, 1.0e-9D);
    }

    // Reach.

    @Test
    void reachIsInclusiveUpToVanillaAndRejectsBeyond() {
        double reach = 4.5D;
        assertTrue(PlayerActionGeometry.withinReach(0.0D, reach));
        assertTrue(PlayerActionGeometry.withinReach(4.0D, reach));
        assertTrue(PlayerActionGeometry.withinReach(4.5D, reach));
        assertFalse(PlayerActionGeometry.withinReach(4.6D, reach));
        assertFalse(PlayerActionGeometry.withinReach(16.0D, reach));
    }

    @Test
    void theReachComparisonToleratesRoundingButNotARealExtraBlock() {
        assertTrue(PlayerActionGeometry.withinReach(4.5D + 0.0001D, 4.5D));
        assertFalse(PlayerActionGeometry.withinReach(4.5D + 0.5D, 4.5D));
    }

    @Test
    void horizontalDistanceIgnoresHeight() {
        assertEquals(25.0D, PlayerActionGeometry.horizontalDistanceSqr(3.0D, 4.0D), DELTA);
        assertEquals(0.0D, PlayerActionGeometry.horizontalDistanceSqr(0.0D, 0.0D), DELTA);
    }

    // The interaction-range attributes.

    @Test
    void vanillaRangesPassTheGuard() {
        assertEquals(null, rangeViolation(4.5D, Set.of(), 3.0D, Set.of(), false));
    }

    @Test
    void aRaisedBlockRangeFailsTheGuard() {
        String violation = rangeViolation(16.0D, Set.of(), 3.0D, Set.of(), false);
        assertTrue(violation != null && violation.contains("BLOCK_INTERACTION_RANGE"), String.valueOf(violation));
        assertTrue(violation.contains("16.000"), violation);
    }

    @Test
    void aRaisedEntityRangeFailsTheGuard() {
        String violation = rangeViolation(4.5D, Set.of(), 5.0D, Set.of(), false);
        assertTrue(violation != null && violation.contains("ENTITY_INTERACTION_RANGE"), String.valueOf(violation));
    }

    @Test
    void aModifierFailsTheGuardEvenAtTheVanillaValue() {
        String blockViolation = rangeViolation(4.5D, Set.of("example:longer_block_reach"), 3.0D, Set.of(), false);
        assertTrue(blockViolation != null && blockViolation.contains("modifier"), String.valueOf(blockViolation));
        String entityViolation = rangeViolation(4.5D, Set.of(), 3.0D, Set.of("example:longer_entity_reach"), false);
        assertTrue(entityViolation != null && entityViolation.contains("modifier"), String.valueOf(entityViolation));
    }

    @Test
    void aCreativePlayersOwnRangeBonusesPassTheGuard() {
        assertEquals(
                null,
                rangeViolation(
                        4.5D + PlayerActionGeometry.CREATIVE_BLOCK_REACH_BONUS,
                        Set.of("minecraft:creative_mode_block_range"),
                        3.0D + PlayerActionGeometry.CREATIVE_ENTITY_REACH_BONUS,
                        Set.of("minecraft:creative_mode_entity_range"),
                        true));
    }

    @Test
    void aCreativeBonusOnAPlayerThatIsNotCreativeFailsTheGuard() {
        assertTrue(rangeViolation(4.5D, Set.of("minecraft:creative_mode_block_range"), 3.0D, Set.of(), false) != null);
    }

    @Test
    void aModifierOnTopOfACreativeBonusFailsTheGuard() {
        assertTrue(rangeViolation(
                4.5D + PlayerActionGeometry.CREATIVE_BLOCK_REACH_BONUS,
                Set.of("minecraft:creative_mode_block_range", "example:longer_block_reach"),
                3.0D + PlayerActionGeometry.CREATIVE_ENTITY_REACH_BONUS,
                Set.of("minecraft:creative_mode_entity_range"),
                true) != null);
    }

    @Test
    void aRangeAtTheLimitWithRoundingErrorStillPasses() {
        assertEquals(null, rangeViolation(4.5D + 0.0001D, Set.of(), 3.0D + 0.0001D, Set.of(), false));
    }

    private static String rangeViolation(
            double blockReach,
            Set<String> blockModifierIds,
            double entityReach,
            Set<String> entityModifierIds,
            boolean creative
    ) {
        return PlayerActionGeometry.interactionRangeViolation(
                blockReach, blockModifierIds, entityReach, entityModifierIds, 4.5D, 3.0D, creative);
    }
}
