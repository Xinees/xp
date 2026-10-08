package com.backroomsport.events;

import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of the addon's DistractionManager. Every noise has a priority: 1 = sound (24 blocks), 2 = loud (64 blocks),
 * 3 = critical (128 blocks). Bacteria within that radius of the noise walk to the NOISE position, not to the player.
 */
public final class DistractionManager {
    public static final int SOUND = 1;
    public static final int LOUD = 2;
    public static final int CRITICAL = 3;

    /** The hearing system is switched off for now. Set to true (and BacteriaEntity.HEARING_ENABLED) to bring it back. */
    public static final boolean ENABLED = false;

    /** Priority 1 noises of the same player are limited to one per 5 seconds. */
    private static final long COOLDOWN_TICKS = 5L * 20L;
    private static final Map<UUID, Long> LAST_DISTRACTION = new HashMap<>();

    private DistractionManager() {
    }

    public static double radius(int priority) {
        return switch (priority) {
            case 2 -> 64.0D;
            case 3 -> 128.0D;
            default -> 24.0D;
        };
    }

    /** A noise made by a player (createDistraction). */
    public static boolean create(ServerLevel level, Vec3 location, ServerPlayer source, int priority, boolean ignoreSneaking) {
        if (!ENABLED) {
            return false;
        }
        long now = level.getGameTime();
        Long last = LAST_DISTRACTION.get(source.getUUID());
        if (priority < 2 && last != null && now - last < COOLDOWN_TICKS) {
            return false;
        }
        if (!canTrigger(level, source, priority, ignoreSneaking)) {
            return false;
        }
        if (!notifyBacteria(level, location, priority)) {
            return false;
        }
        LAST_DISTRACTION.put(source.getUUID(), now);
        return true;
    }

    /** A noise without a player (createEnvironmentalDistraction), e.g. an explosion. */
    public static boolean createEnvironmental(ServerLevel level, Vec3 location, int priority) {
        if (!ENABLED) {
            return false;
        }
        return notifyBacteria(level, location, priority);
    }

    /** canTrigger: a Bacteria must be within the radius of the player, sneaking halves the radius. */
    private static boolean canTrigger(ServerLevel level, ServerPlayer player, int priority, boolean ignoreSneaking) {
        if (player.isSpectator()) {
            return false;
        }
        double r = radius(priority);
        if (player.isCrouching() && !ignoreSneaking) {
            r *= 0.5D;
        }
        final double radius = r;
        List<BacteriaEntity> near = level.getEntitiesOfClass(BacteriaEntity.class,
                player.getBoundingBox().inflate(radius), b -> b.distanceToSqr(player) <= radius * radius);
        return !near.isEmpty();
    }

    private static boolean notifyBacteria(ServerLevel level, Vec3 location, int priority) {
        final double r = radius(priority);
        AABB box = new AABB(location, location).inflate(r);
        List<BacteriaEntity> list = level.getEntitiesOfClass(BacteriaEntity.class, box,
                b -> b.distanceToSqr(location) <= r * r);
        if (list.isEmpty()) {
            return false;
        }
        for (BacteriaEntity bacteria : list) {
            bacteria.onDistraction(location, priority);
        }
        return true;
    }
}
