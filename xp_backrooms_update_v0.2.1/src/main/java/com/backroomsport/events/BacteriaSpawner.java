package com.backroomsport.events;

import com.backroomsport.entity.BacteriaEntity;
import com.backroomsport.registry.ModDimensions;
import com.backroomsport.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Spawn rules from the Bedrock addon: a player must spend 3 minutes in the Backrooms dimension,
 * and after every encounter gets a cooldown (5% short 4-7 min, 5% long 40-60 min, otherwise 20-40 min).
 */
public class BacteriaSpawner {
    private static final String ENTERED = "xp_backrooms_entered";
    private static final String NEXT = "xp_backrooms_next_spawn";
    private static final long GRACE_TICKS = 3 * 60 * 20L;
    private static final long MINUTE = 60 * 20L;

    public static void onEncounter(ServerPlayer player) {
        RandomSource r = player.getRandom();
        float roll = r.nextFloat();
        long minMin;
        long maxMin;
        if (roll < 0.05F) {
            minMin = 4;
            maxMin = 7;
        } else if (roll < 0.10F) {
            minMin = 40;
            maxMin = 60;
        } else {
            minMin = 20;
            maxMin = 40;
        }
        long span = (maxMin - minMin) * MINUTE;
        long delay = minMin * MINUTE + (long) (r.nextDouble() * span);
        player.getPersistentData().putLong(NEXT, player.serverLevel().getGameTime() + delay);
    }

    public static void copyOnClone(ServerPlayer original, ServerPlayer clone) {
        CompoundTag from = original.getPersistentData();
        if (from.contains(NEXT)) {
            clone.getPersistentData().putLong(NEXT, from.getLong(NEXT));
        }
    }

    public static void tick(ServerPlayer player) {
        if (player.tickCount % 20 != 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        CompoundTag data = player.getPersistentData();

        if (level.dimension() != ModDimensions.BACKROOMS) {
            data.putLong(ENTERED, -1L);
            return;
        }
        long now = level.getGameTime();
        if (!data.contains(ENTERED) || data.getLong(ENTERED) < 0L) {
            data.putLong(ENTERED, now);
        }
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        if (now - data.getLong(ENTERED) < GRACE_TICKS) {
            return;
        }
        if (now < data.getLong(NEXT)) {
            return;
        }
        if (!level.getEntitiesOfClass(BacteriaEntity.class, player.getBoundingBox().inflate(300.0D)).isEmpty()) {
            return;
        }
        spawnNear(player, level);
    }

    private static void spawnNear(ServerPlayer player, ServerLevel level) {
        RandomSource r = level.random;
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2.0D;
            double dist = 40.0D + r.nextDouble() * 20.0D;
            int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            double px = x + 0.5D;
            double pz = z + 0.5D;
            if (!level.noCollision(ModEntities.BACTERIA.get().getAABB(px, y, pz))) {
                continue;
            }
            BacteriaEntity bacteria = ModEntities.BACTERIA.get().create(level);
            if (bacteria == null) {
                return;
            }
            bacteria.moveTo(px, y, pz, r.nextFloat() * 360.0F, 0.0F);
            bacteria.scheduleSpawnWail();
            level.addFreshEntity(bacteria);
            return;
        }
    }
}
