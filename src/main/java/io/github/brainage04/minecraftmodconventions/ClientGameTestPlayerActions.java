package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Objects;

/**
 * The player actions a client GameTest may take, and the guard that keeps every one of them
 * vanilla-possible.
 *
 * <p>A recording shows a player playing. So a client GameTest turns to a target, walks to it with the
 * forward key and then mines, uses or attacks only the block or entity under the crosshair, from within
 * vanilla reach: 4.5 blocks for a block and 3.0 for an entity ({@link Player#DEFAULT_BLOCK_INTERACTION_RANGE},
 * {@link Player#DEFAULT_ENTITY_INTERACTION_RANGE}). It never raises {@code BLOCK_INTERACTION_RANGE} or
 * {@code ENTITY_INTERACTION_RANGE} and never breaks, places or uses a block it is not looking at.
 *
 * <p>{@link #mine}, {@link #useOnBlock} and {@link #attackEntity} check that rule before they press a
 * key: the crosshair's hit result must be the target, the target must be inside vanilla reach, and the
 * player's interaction-range attributes must be untouched. A test that breaks the rule fails with an
 * {@link AssertionError} naming what was wrong instead of recording something a player could not do.
 *
 * <p>The helpers act on the client thread themselves; a test calls them between its own steps:
 *
 * <pre>{@code
 * ClientGameTestPlayerActions.walkWithinReach(context, Vec3.atCenterOf(ore));
 * ClientGameTestPlayerActions.lookAtBlock(context, ore);
 * ClientGameTestPlayerActions.mine(context, ore);
 * }</pre>
 *
 * <p>Server-side calls remain fine for what the camera never shows: building the scene, spawning,
 * time, weather, and teleports between stages before the recording starts.
 */
public final class ClientGameTestPlayerActions {
    /** Vanilla survival reach for a block interaction, in blocks. */
    public static final double VANILLA_BLOCK_REACH = Player.DEFAULT_BLOCK_INTERACTION_RANGE;
    /** Vanilla survival reach for an entity interaction, in blocks. */
    public static final double VANILLA_ENTITY_REACH = Player.DEFAULT_ENTITY_INTERACTION_RANGE;
    /** How close the helper walks to a target it is about to interact with, in blocks. */
    public static final double WALK_REACH = 2.0D;
    /** Ticks a walk may take before the helper fails the test. */
    public static final int WALK_TIMEOUT_TICKS = 400;
    /** Ticks a block may take to break before the helper fails the test. */
    public static final int MINE_TIMEOUT_TICKS = 300;
    /** Ticks the client needs to send a new look direction before its crosshair is recomputed. */
    public static final int AIM_SETTLE_TICKS = 2;

    private ClientGameTestPlayerActions() {
    }

    // Aiming.

    /**
     * Turns the player to look at a point, the way a mouse does, and lets the client send the new
     * direction before returning.
     */
    public static void lookAt(ClientGameTestContext context, Vec3 point) {
        Objects.requireNonNull(point, "point");
        context.runOnClient(client -> turnTo(client.player, point));
        context.waitTicks(AIM_SETTLE_TICKS);
    }

    /** Turns the player to look at the middle of a block. */
    public static void lookAtBlock(ClientGameTestContext context, BlockPos pos) {
        lookAt(context, Vec3.atCenterOf(pos));
    }

    /** Turns the player to look straight up, for a block directly above the head. */
    public static void lookStraightUp(ClientGameTestContext context) {
        context.runOnClient(client -> {
            LocalPlayer player = client.player;
            player.setYRot(0.0F);
            player.setXRot(-90.0F);
            syncLook(player);
        });
        context.waitTicks(AIM_SETTLE_TICKS);
    }

    /** Turns the player horizontally towards a point and levels the view, for walking. */
    public static void lookLevel(ClientGameTestContext context, Vec3 point) {
        Objects.requireNonNull(point, "point");
        context.runOnClient(client -> {
            LocalPlayer player = client.player;
            Vec3 eye = player.getEyePosition();
            float yaw = (float) PlayerActionGeometry.yawTo(point.x - eye.x, point.z - eye.z);
            player.setYRot(yaw);
            player.setXRot(0.0F);
            syncLook(player);
        });
        context.waitTicks(AIM_SETTLE_TICKS);
    }

    // Walking, on the forward and backward keys.

    /**
     * Walks towards a point with the forward key until the player stands within {@code stopWithin}
     * blocks of it, then stops. Does nothing when the player is already that close.
     */
    public static void walkTo(ClientGameTestContext context, Vec3 point, double stopWithin) {
        Objects.requireNonNull(point, "point");
        if (context.computeOnClient(client -> PlayerActionGeometry.withinReach(horizontalDistance(client.player.position(), point), stopWithin))) {
            return;
        }
        lookLevel(context, point);
        context.getInput().holdKey(options -> options.keyUp);
        try {
            context.waitFor(
                    client -> PlayerActionGeometry.withinReach(horizontalDistance(client.player.position(), point), stopWithin),
                    WALK_TIMEOUT_TICKS);
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        context.waitTicks(AIM_SETTLE_TICKS);
    }

    /** Walks until the player stands {@link #WALK_REACH} blocks from a point. */
    public static void walkWithinReach(ClientGameTestContext context, Vec3 point) {
        walkTo(context, point, WALK_REACH);
    }

    /**
     * Steps backwards with the back key until the player is at least {@code distance} blocks away
     * from a point, for a stage that lights something that explodes.
     */
    public static void walkAwayFrom(ClientGameTestContext context, Vec3 point, double distance) {
        Objects.requireNonNull(point, "point");
        context.getInput().holdKey(options -> options.keyDown);
        try {
            context.waitFor(
                    client -> !PlayerActionGeometry.withinReach(horizontalDistance(client.player.position(), point), distance),
                    WALK_TIMEOUT_TICKS);
        } finally {
            context.getInput().releaseKey(options -> options.keyDown);
        }
        context.waitTicks(AIM_SETTLE_TICKS);
    }

    // Acting on the crosshair target.

    /**
     * Holds the attack key until the block is gone. Fails the test when the block is not under the
     * crosshair, when it is out of vanilla reach, or when an interaction-range attribute was changed.
     *
     * <p>The client removes the block before the server confirms the break, and only the server hands
     * out the drops: wait for the server's own state before asserting on them.
     */
    public static void mine(ClientGameTestContext context, BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        requireLookingAtBlock(context, pos);
        context.getInput().holdKey(options -> options.keyAttack);
        try {
            context.waitFor(client -> client.level.getBlockState(pos).isAir(), MINE_TIMEOUT_TICKS);
        } finally {
            context.getInput().releaseKey(options -> options.keyAttack);
        }
    }

    /**
     * Presses the use key once with the block under the crosshair. Fails the test when the block is not
     * under the crosshair or is out of vanilla reach.
     */
    public static void useOnBlock(ClientGameTestContext context, BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        requireLookingAtBlock(context, pos);
        context.getInput().pressKey(options -> options.keyUse);
    }

    /**
     * Presses the attack key once with the entity under the crosshair. Fails the test when the entity
     * is not under the crosshair or is out of vanilla reach.
     */
    public static void attackEntity(ClientGameTestContext context, int entityId) {
        requireLookingAtEntity(context, entityId);
        context.getInput().pressKey(options -> options.keyAttack);
    }

    // The guard.

    /**
     * Fails the test unless the crosshair is on {@code pos}, the player is inside vanilla block reach
     * of it, and the interaction-range attributes are untouched.
     */
    public static void requireLookingAtBlock(ClientGameTestContext context, BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        String violation = context.computeOnClient(client -> blockCrosshairViolation(client, pos));
        if (violation != null) {
            throw new AssertionError(violation);
        }
    }

    /**
     * Fails the test unless the crosshair is on the entity, the player is inside vanilla entity reach
     * of it, and the interaction-range attributes are untouched.
     */
    public static void requireLookingAtEntity(ClientGameTestContext context, int entityId) {
        String violation = context.computeOnClient(client -> entityCrosshairViolation(client, entityId));
        if (violation != null) {
            throw new AssertionError(violation);
        }
    }

    /**
     * Fails the test when the player's interaction-range attributes carry a modifier or a base value
     * above vanilla's, which would let it reach further than a player can.
     */
    public static void requireVanillaInteractionRanges(ClientGameTestContext context) {
        String violation = context.computeOnClient(ClientGameTestPlayerActions::interactionRangeViolation);
        if (violation != null) {
            throw new AssertionError(violation);
        }
    }

    /** The distance vanilla measures for reaching the block, from the eyes to its box. */
    public static double blockDistance(LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        AABB box = new AABB(pos);
        return Math.sqrt(box.distanceToSqr(eye));
    }

    private static String blockCrosshairViolation(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        if (player == null) {
            return "No client player to act with";
        }
        String rangeViolation = interactionRangeViolation(client);
        if (rangeViolation != null) {
            return rangeViolation;
        }
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit)) {
            return "The crosshair is not on a block, so the test may not break or use " + pos + ": " + describe(hit);
        }
        if (!blockHit.getBlockPos().equals(pos)) {
            return "The crosshair is on block " + blockHit.getBlockPos() + ", not on the test's target " + pos;
        }
        double distance = blockDistance(player, pos);
        if (!PlayerActionGeometry.withinReach(distance, player.blockInteractionRange())) {
            return "The block " + pos + " is " + format(distance) + " blocks away, outside vanilla block reach "
                    + format(player.blockInteractionRange());
        }
        return null;
    }

    private static String entityCrosshairViolation(Minecraft client, int entityId) {
        LocalPlayer player = client.player;
        if (player == null) {
            return "No client player to act with";
        }
        String rangeViolation = interactionRangeViolation(client);
        if (rangeViolation != null) {
            return rangeViolation;
        }
        HitResult hit = client.hitResult;
        if (!(hit instanceof EntityHitResult entityHit)) {
            return "The crosshair is not on an entity, so the test may not attack entity " + entityId + ": " + describe(hit);
        }
        Entity entity = entityHit.getEntity();
        if (entity.getId() != entityId) {
            return "The crosshair is on entity " + entity.getId() + " (" + entity.getType() + "), not on the test's target " + entityId;
        }
        double distance = Math.sqrt(entity.getBoundingBox().distanceToSqr(player.getEyePosition()));
        if (!PlayerActionGeometry.withinReach(distance, player.entityInteractionRange())) {
            return "Entity " + entityId + " is " + format(distance) + " blocks away, outside vanilla entity reach "
                    + format(player.entityInteractionRange());
        }
        return null;
    }

    private static String interactionRangeViolation(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return "No client player to act with";
        }
        return PlayerActionGeometry.interactionRangeViolation(
                player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE),
                hasModifiers(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE)),
                player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
                hasModifiers(player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE)),
                VANILLA_BLOCK_REACH,
                VANILLA_ENTITY_REACH);
    }

    private static boolean hasModifiers(AttributeInstance instance) {
        return instance != null && !instance.getModifiers().isEmpty();
    }

    private static String describe(HitResult hit) {
        return hit == null ? "no hit result" : hit.getType() + " at " + format(hit.getLocation().x) + ","
                + format(hit.getLocation().y) + "," + format(hit.getLocation().z);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        return Math.sqrt(PlayerActionGeometry.horizontalDistanceSqr(to.x - from.x, to.z - from.z));
    }

    private static void turnTo(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        player.setYRot((float) PlayerActionGeometry.yawTo(dx, dz));
        player.setXRot((float) PlayerActionGeometry.pitchTo(dy, dx, dz));
        syncLook(player);
    }

    /**
     * Copies the new look into the fields the client interpolates from and turns the body with it, so
     * the next tick sends the rotation the way a mouse turn would.
     */
    private static void syncLook(LocalPlayer player) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;
        player.setYBodyRot(yaw);
        player.yBodyRotO = yaw;
    }
}
