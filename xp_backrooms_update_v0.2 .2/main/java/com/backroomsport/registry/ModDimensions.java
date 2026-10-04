package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public class ModDimensions {
    /** Defined by data/xp_backrooms/dimension/backrooms.json */
    public static final ResourceKey<Level> BACKROOMS =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(XpBackrooms.MOD_ID, "backrooms"));
}
