package com.backroomsport.client;

import com.backroomsport.entity.BacteriaEntity;
import com.backroomsport.registry.ModSounds;
import net.minecraft.client.Minecraft;

/** Client-only helpers called from BacteriaEntity (kept in their own class so a dedicated server never loads it). */
public final class BacteriaClientHooks {
    private BacteriaClientHooks() {
    }

    /** Bedrock "stopsound @p wailing": stops the wailing for the player who is nearest to the monster. */
    public static void stopWailing(BacteriaEntity bacteria) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (bacteria.level().getNearestPlayer(bacteria, -1.0D) != minecraft.player) {
            return;
        }
        minecraft.getSoundManager().stop(ModSounds.BACTERIA_WAILING.getId(), null);
    }
}
