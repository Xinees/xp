package com.backroomsport;

import com.backroomsport.registry.ModEntities;
import com.backroomsport.registry.ModItems;
import com.backroomsport.registry.ModSounds;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(XpBackrooms.MOD_ID)
public class XpBackrooms {
    public static final String MOD_ID = "xp_backrooms";

    public XpBackrooms() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModSounds.SOUNDS.register(bus);
        ModEntities.ENTITIES.register(bus);
        ModItems.ITEMS.register(bus);
        bus.addListener(this::addCreative);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.BACTERIA_SPAWN_EGG);
        }
    }
}
