package com.backroomsport.events;

import com.backroomsport.XpBackrooms;
import com.backroomsport.entity.BacteriaEntity;
import com.backroomsport.registry.ModEntities;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = XpBackrooms.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {
    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.BACTERIA.get(), BacteriaEntity.createAttributes().build());
    }
}
