package io.github.brainage04.minecraftmodconventions;

import java.util.Locale;

/**
 * The arithmetic behind {@link ClientGameTestPlayerActions}: look direction, distance and reach. It holds
 * no game state, so the guard's numbers can be tested without a client.
 */
final class PlayerActionGeometry {
    /** The slack allowed for floating-point error when comparing a distance with a reach. */
    static final double EPSILON = 1.0e-3D;

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
     * The reason the player's interaction ranges are not vanilla's, or {@code null} when they are: a
     * range above vanilla's, or any modifier on either range attribute, means a test raised it.
     */
    static String interactionRangeViolation(
            double blockReach,
            boolean blockModified,
            double entityReach,
            boolean entityModified,
            double vanillaBlockReach,
            double vanillaEntityReach
    ) {
        if (blockModified) {
            return "The player's BLOCK_INTERACTION_RANGE attribute has a modifier, so the test changed it; "
                    + "client GameTests use vanilla reach (" + format(vanillaBlockReach) + " blocks)";
        }
        if (blockReach > vanillaBlockReach + EPSILON) {
            return "The player's BLOCK_INTERACTION_RANGE is " + format(blockReach) + " blocks, above vanilla's "
                    + format(vanillaBlockReach) + "; a client GameTest may not reach further than a player";
        }
        if (entityModified) {
            return "The player's ENTITY_INTERACTION_RANGE attribute has a modifier, so the test changed it; "
                    + "client GameTests use vanilla reach (" + format(vanillaEntityReach) + " blocks)";
        }
        if (entityReach > vanillaEntityReach + EPSILON) {
            return "The player's ENTITY_INTERACTION_RANGE is " + format(entityReach) + " blocks, above vanilla's "
                    + format(vanillaEntityReach) + "; a client GameTest may not reach further than a player";
        }
        return null;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
