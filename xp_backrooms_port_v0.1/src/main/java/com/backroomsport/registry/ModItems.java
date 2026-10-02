package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, XpBackrooms.MOD_ID);

    public static final RegistryObject<Item> BACTERIA_SPAWN_EGG = ITEMS.register("bacteria_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.BACTERIA, 0x2B2B1F, 0x8A8A52, new Item.Properties()));
}
