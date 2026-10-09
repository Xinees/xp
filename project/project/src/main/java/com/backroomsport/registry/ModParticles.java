package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, XpBackrooms.MOD_ID);

    public static final RegistryObject<SimpleParticleType> BACTERIA_STEP =
            PARTICLES.register("bacteria_step", () -> new SimpleParticleType(false));
}
