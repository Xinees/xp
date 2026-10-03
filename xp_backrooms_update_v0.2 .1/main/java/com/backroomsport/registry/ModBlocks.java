package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, XpBackrooms.MOD_ID);

    public static final RegistryObject<Block> LOBBY_CARPET = BLOCKS.register("lobby_carpet",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(0.5F).sound(SoundType.WOOL)));

    public static final RegistryObject<Block> LOBBY_STRIPED_WALLPAPER = BLOCKS.register("lobby_striped_wallpaper",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.8F).sound(SoundType.WOOD)));

    public static final RegistryObject<Block> LOBBY_CEILING_PANEL = BLOCKS.register("lobby_ceiling_panel",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.8F).sound(SoundType.STONE)));

    public static final RegistryObject<Block> LOBBY_CEILING_LIGHT = BLOCKS.register("lobby_ceiling_light",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.8F)
                    .sound(SoundType.GLASS).lightLevel(state -> 15)));
}
