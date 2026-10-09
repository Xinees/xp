package com.backroomsport.client;

import com.backroomsport.entity.BacteriaEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class BacteriaRenderer extends GeoEntityRenderer<BacteriaEntity> {
    public BacteriaRenderer(EntityRendererProvider.Context context) {
        super(context, new BacteriaModel());
        this.shadowRadius = 0.5F;
    }
}
