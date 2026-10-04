package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, XpBackrooms.MOD_ID);

    public static final RegistryObject<Item> BACTERIA_SPAWN_EGG = ITEMS.register("bacteria_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.BACTERIA, 0x2B2B1F, 0x8A8A52, new Item.Properties()));

    public static final RegistryObject<Item> LOBBY_CARPET = blockItem("lobby_carpet", ModBlocks.LOBBY_CARPET);
    public static final RegistryObject<Item> LOBBY_STRIPED_WALLPAPER = blockItem("lobby_striped_wallpaper", ModBlocks.LOBBY_STRIPED_WALLPAPER);
    public static final RegistryObject<Item> LOBBY_CEILING_PANEL = blockItem("lobby_ceiling_panel", ModBlocks.LOBBY_CEILING_PANEL);
    public static final RegistryObject<Item> LOBBY_CEILING_LIGHT = blockItem("lobby_ceiling_light", ModBlocks.LOBBY_CEILING_LIGHT);

    private static RegistryObject<Item> blockItem(String name, RegistryObject<Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
}
