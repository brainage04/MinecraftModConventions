package io.github.brainage04.minecraftmodconventions;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The arithmetic behind {@link ClientGameTestPlayerActions}: look direction, distance and reach. It holds
 * no game state, so the guard's numbers can be tested without a client.
 */
final class PlayerActionGeometry {
    /** The slack allowed for floating-point error when comparing a distance with a reach. */
    static final double EPSILON = 1.0e-3D;
    /** The block reach vanilla adds for a creative player: {@code ServerPlayer.java:223-231, 629-645} (26.3). */
    static final double CREATIVE_BLOCK_REACH_BONUS = 0.5D;
    /** The entity reach vanilla adds for a creative player: the same two places. */
    static final double CREATIVE_ENTITY_REACH_BONUS = 2.0D;
    /**
     * The ids of vanilla's two creative range modifiers, which nobody but the game adds to a player:
     * {@code creative_mode_block_range} and {@code creative_mode_entity_range}, both declared in
     * {@code ServerPlayer} (26.3).
     */
    static final List<String> CREATIVE_RANGE_MODIFIER_IDS =
            List.of("minecraft:creative_mode_block_range", "minecraft:creative_mode_entity_range");

    private PlayerActionGeometry() {
    }

    /**
     * The yaw a player needs to look from its eyes towards a point offset by {@code dx} and
     * {@code dz}, in degrees, as {@code Entity.setYRot} takes it: 0 faces south (+z), 90 west (-x).
     */
    static double yawTo(double dx, double dz) {
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        if (yaw <= -180.0D) {
            return yaw + 360.0D;
        }
        if (yaw > 180.0D) {
            return yaw - 360.0D;
        }
        return yaw;
    }

    /**
     * The pitch a player needs to look from its eyes towards a point offset by {@code dx},
     * {@code dy} and {@code dz}, in degrees, as {@code Entity.setXRot} takes it: -90 looks straight up.
     */
    static double pitchTo(double dy, double dx, double dz) {
        return -Math.toDegrees(Math.atan2(dy, Math.sqrt(horizontalDistanceSqr(dx, dz))));
    }

    static double horizontalDistanceSqr(double dx, double dz) {
        return dx * dx + dz * dz;
    }

    /** Whether a distance is inside a reach, allowing for rounding. */
    static boolean withinReach(double distance, double reach) {
        return distance <= reach + EPSILON;
    }

    /**
     * The reason the player's interaction ranges are not what vanilla gives its game mode, or
     * {@code null} when they are: a range above it, or a modifier a test added to either range.
     *
     * <p>Vanilla gives every creative player two transient range modifiers, and a guard that counted
     * them would fail a creative test that changed nothing. They are named by id and ignored for a
     * creative player; a player that is not creative still reports them.
     */
    static String interactionRangeViolation(
            double blockReach,
            Collection<String> blockModifierIds,
            double entityReach,
            Collection<String> entityModifierIds,
            double vanillaBlockReach,
            double vanillaEntityReach,
            boolean creative
    ) {
        double expectedBlockReach = vanillaReach(vanillaBlockReach, CREATIVE_BLOCK_REACH_BONUS, creative);
        double expectedEntityReach = vanillaReach(vanillaEntityReach, CREATIVE_ENTITY_REACH_BONUS, creative);
        if (hasTestModifier(blockModifierIds, creative)) {
            return "The player's BLOCK_INTERACTION_RANGE attribute has a modifier, so the test changed it; "
                    + "client GameTests use vanilla reach (" + format(expectedBlockReach) + " blocks)";
        }
        if (blockReach > expectedBlockReach + EPSILON) {
            return "The player's BLOCK_INTERACTION_RANGE is " + format(blockReach) + " blocks, above vanilla's "
                    + format(expectedBlockReach) + "; a client GameTest may not reach further than a player";
        }
        if (hasTestModifier(entityModifierIds, creative)) {
            return "The player's ENTITY_INTERACTION_RANGE attribute has a modifier, so the test changed it; "
                    + "client GameTests use vanilla reach (" + format(expectedEntityReach) + " blocks)";
        }
        if (entityReach > expectedEntityReach + EPSILON) {
            return "The player's ENTITY_INTERACTION_RANGE is " + format(entityReach) + " blocks, above vanilla's "
                    + format(expectedEntityReach) + "; a client GameTest may not reach further than a player";
        }
        return null;
    }

    /** The reach vanilla gives a player of this game mode, before a test adds anything to it. */
    static double vanillaReach(double base, double creativeBonus, boolean creative) {
        return creative ? base + creativeBonus : base;
    }

    /** Whether the attribute carries a modifier a test added, rather than one of vanilla's creative bonuses. */
    static boolean hasTestModifier(Collection<String> modifierIds, boolean creative) {
        return modifierIds.stream().anyMatch(id -> !creative || !CREATIVE_RANGE_MODIFIER_IDS.contains(id));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
