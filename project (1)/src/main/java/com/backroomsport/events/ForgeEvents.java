package com.backroomsport.events;

import com.backroomsport.XpBackrooms;
import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = XpBackrooms.MOD_ID)
public class ForgeEvents {

    /** minecraft:reflect_projectiles - arrows and other projectiles fly back. */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getRayTraceResult() instanceof EntityHitResult hit && hit.getEntity() instanceof BacteriaEntity bacteria) {
            Projectile projectile = event.getProjectile();
            event.setCanceled(true);
            Vec3 motion = projectile.getDeltaMovement();
            projectile.setDeltaMovement(motion.scale(-1.0D));
            projectile.setYRot(projectile.getYRot() + 180.0F);
            projectile.yRotO = projectile.getYRot();
            projectile.setOwner(bacteria);
            projectile.hasImpulse = true;
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
            BacteriaSpawner.tick(player);
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original && event.getEntity() instanceof ServerPlayer clone) {
            BacteriaSpawner.copyOnClone(original, clone);
        }
    }
}
