package com.backroomsport.client;

import com.backroomsport.XpBackrooms;
import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

public class BacteriaModel extends GeoModel<BacteriaEntity> {
    private static final ResourceLocation MODEL =
            new ResourceLocation(XpBackrooms.MOD_ID, "geo/bacteria.geo.json");
    private static final ResourceLocation ANIMATIONS =
            new ResourceLocation(XpBackrooms.MOD_ID, "animations/bacteria.animation.json");

    // The Bedrock texture is a 7-frame flipbook (5 fps); it was split into 7 separate pngs.
    private static final int FRAME_COUNT = 7;
    private static final int TICKS_PER_FRAME = 4;
    private static final ResourceLocation[] FRAMES = new ResourceLocation[FRAME_COUNT];

    static {
        for (int i = 0; i < FRAME_COUNT; i++) {
            FRAMES[i] = new ResourceLocation(XpBackrooms.MOD_ID, "textures/entity/bacteria_" + i + ".png");
        }
    }

    @Override
    public ResourceLocation getModelResource(BacteriaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(BacteriaEntity animatable) {
        return FRAMES[(animatable.tickCount / TICKS_PER_FRAME) % FRAME_COUNT];
    }

    @Override
    public ResourceLocation getAnimationResource(BacteriaEntity animatable) {
        return ANIMATIONS;
    }

    @Override
    public void setCustomAnimations(BacteriaEntity animatable, long instanceId, AnimationState<BacteriaEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        CoreGeoBone head = getAnimationProcessor().getBone("head");
        if (head != null) {
            EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
            head.setRotX(head.getRotX() + data.headPitch() * Mth.DEG_TO_RAD);
            head.setRotY(head.getRotY() + data.netHeadYaw() * Mth.DEG_TO_RAD);
        }

        if (animatable.getState() == BacteriaEntity.STATE_KILLING) {
            addKillShake(animatable.getKillAnimSeconds(animationState.getPartialTick()));
        }
    }

    // ---- kill.overlay of the Bedrock addon: only the shaking on top of the kill animation ----
    // Keyframes (time, amplitude in degrees, frequency in degrees per second): value = amplitude * sin(frequency * t),
    // linearly blended between neighbouring keyframes like Molang keyframes are.
    private static final double[] BODY_TIMES = {0.0D, 0.5417D, 1.0D, 1.2083D, 1.4583D, 2.0D};
    private static final double[] BODY_AMP = {0.0D, 0.0D, 8.0D, 8.0D, 8.0D, 12.0D};
    private static final double[] BODY_FREQ = {0.0D, 0.0D, 2400.0D, 2400.0D, 3000.0D, 3000.0D};
    private static final double[] ARM_TIMES = {0.0D, 0.4167D, 0.5417D, 1.0D, 1.2083D, 1.4583D, 2.0D};
    private static final double[] ARM_AMP = {-2.0D, -2.0D, 0.0D, 0.0D, -2.0D, -2.0D, -2.0D};
    private static final double[] ARM_FREQ = {2400.0D, 2400.0D, 0.0D, 0.0D, 2400.0D, 3000.0D, 3000.0D};

    private void addKillShake(double t) {
        addYaw("body_upper", shake(t, BODY_TIMES, BODY_AMP, BODY_FREQ));
        double arm = shake(t, ARM_TIMES, ARM_AMP, ARM_FREQ);
        addYaw("right_arm_offset", arm);
        addYaw("left_arm_offset", arm);
    }

    private void addYaw(String boneName, double degrees) {
        CoreGeoBone bone = getAnimationProcessor().getBone(boneName);
        if (bone != null) {
            bone.setRotY(bone.getRotY() + (float) degrees * Mth.DEG_TO_RAD);
        }
    }

    private static double shake(double t, double[] times, double[] amp, double[] freq) {
        int last = times.length - 1;
        if (t <= times[0]) {
            return value(amp[0], freq[0], t);
        }
        if (t >= times[last]) {
            return value(amp[last], freq[last], t);
        }
        for (int i = 0; i < last; i++) {
            if (t < times[i + 1]) {
                double progress = (t - times[i]) / (times[i + 1] - times[i]);
                double a = value(amp[i], freq[i], t);
                double b = value(amp[i + 1], freq[i + 1], t);
                return a + (b - a) * progress;
            }
        }
        return 0.0D;
    }

    private static double value(double amplitude, double frequency, double t) {
        return amplitude * Math.sin(Math.toRadians(frequency * t));
    }
}
