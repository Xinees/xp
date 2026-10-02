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
    }
}
