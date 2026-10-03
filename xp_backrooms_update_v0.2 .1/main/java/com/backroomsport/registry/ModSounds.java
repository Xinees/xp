package com.backroomsport.registry;

import com.backroomsport.XpBackrooms;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, XpBackrooms.MOD_ID);

    public static final RegistryObject<SoundEvent> BACTERIA_WALK = reg("bacteria.walk");
    public static final RegistryObject<SoundEvent> BACTERIA_ATTACK = reg("bacteria.attack");
    public static final RegistryObject<SoundEvent> BACTERIA_HURT = reg("bacteria.hurt");
    public static final RegistryObject<SoundEvent> BACTERIA_IDLE = reg("bacteria.idle_1");
    public static final RegistryObject<SoundEvent> BACTERIA_SCREAM = reg("bacteria.scream");
    public static final RegistryObject<SoundEvent> BACTERIA_SPOTTED = reg("bacteria.spotted");
    public static final RegistryObject<SoundEvent> BACTERIA_WAILING = reg("bacteria.wailing");
    public static final RegistryObject<SoundEvent> BACTERIA_TAKEDOWN = reg("bacteria.takedown");

    private static RegistryObject<SoundEvent> reg(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(XpBackrooms.MOD_ID, name)));
    }
}
