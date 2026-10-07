package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, XpBackrooms.MOD_ID);

    public static final RegistryObject<EntityType<BacteriaEntity>> BACTERIA = ENTITIES.register("bacteria",
            () -> EntityType.Builder.of(BacteriaEntity::new, MobCategory.MONSTER)
                    .sized(0.75F, 2.9F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .build(XpBackrooms.MOD_ID + ":bacteria"));
}
