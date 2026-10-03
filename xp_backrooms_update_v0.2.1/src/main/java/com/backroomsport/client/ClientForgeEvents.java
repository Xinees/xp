package com.backroomsport.client;

import com.backroomsport.XpBackrooms;
import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client half of the takedown: no movement input and the camera is locked onto the monster's face. */
@Mod.EventBusSubscriber(modid = XpBackrooms.MOD_ID, value = Dist.CLIENT)
public class ClientForgeEvents {

    private static BacteriaEntity findGrabber() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }
        for (BacteriaEntity bacteria : mc.level.getEntitiesOfClass(BacteriaEntity.class, player.getBoundingBox().inflate(8.0D))) {
            if (bacteria.getGrabbedPlayerId() == player.getId()) {
                return bacteria;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (event.getEntity() != Minecraft.getInstance().player || findGrabber() == null) {
            return;
        }
        Input input = event.getInput();
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
        event.getEntity().setSprinting(false);
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        BacteriaEntity grabber = findGrabber();
        if (grabber == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        Vec3 target = grabber.getEyePosition((float) event.getPartialTick());
        double dx = target.x - cam.x;
        double dy = target.y - cam.y;
        double dz = target.z - cam.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horizontal) * (180.0D / Math.PI)));
        event.setYaw(yaw);
        event.setPitch(pitch);
    }
}
