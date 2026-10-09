package com.backroomsport.client;

import com.backroomsport.XpBackrooms;
import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client half of the takedown (Bedrock doTakedown): the server places the player 1.15 blocks in front of the
 * monster; here the input is blocked, the view is fixed (looking slightly up at the monster, yaw = monster + 180),
 * a small rotational camera shake is added and the HUD and the hand are hidden.
 */
@Mod.EventBusSubscriber(modid = XpBackrooms.MOD_ID, value = Dist.CLIENT)
public class ClientForgeEvents {
    private static final float GRAB_PITCH = -10.0F;
    private static final float SHAKE_DEGREES = 0.35F; // camerashake rotational 0.125

    private static BacteriaEntity grabber;

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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        grabber = findGrabber();
        LocalPlayer player = Minecraft.getInstance().player;
        if (grabber != null && player != null) {
            player.setDeltaMovement(0.0D, Math.min(0.0D, player.getDeltaMovement().y), 0.0D);
            player.setSprinting(false);
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (grabber == null || event.getEntity() != Minecraft.getInstance().player) {
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
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        BacteriaEntity g = grabber;
        Minecraft mc = Minecraft.getInstance();
        if (g == null || mc.level == null) {
            return;
        }
        double t = (mc.level.getGameTime() + event.getPartialTick()) / 20.0D;
        float yaw = Mth.wrapDegrees(g.getYRot() + 180.0F);
        float pitch = GRAB_PITCH;
        yaw += SHAKE_DEGREES * (float) (Math.sin(t * 43.0D) * 0.6D + Math.sin(t * 71.3D + 1.7D) * 0.4D);
        pitch += SHAKE_DEGREES * (float) (Math.sin(t * 39.0D + 0.5D) * 0.6D + Math.sin(t * 67.1D + 2.4D) * 0.4D);
        float roll = SHAKE_DEGREES * (float) (Math.sin(t * 47.0D + 1.1D) * 0.6D + Math.sin(t * 59.9D + 3.1D) * 0.4D);
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(roll);
    }

    @SubscribeEvent
    public static void onHud(RenderGuiOverlayEvent.Pre event) {
        if (grabber != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (grabber != null) {
            event.setCanceled(true);
        }
    }
}
