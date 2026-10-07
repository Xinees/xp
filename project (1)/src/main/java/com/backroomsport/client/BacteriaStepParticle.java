package com.backroomsport.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * Port of particles/entity/bacteria/step.particle.json:
 * 4-8 black squares per step, lifetime 0.5-4 s, speed 0.1-0.7 b/s up and sideways,
 * acceleration (cos((r1 + age) * 200) * 0.2, 0.3, 0), drag 1.5, size 0.02 + r1 * 0.02,
 * alpha 0 -> 1 at 12% of the life, held until 43%, then fading to 0.
 */
public class BacteriaStepParticle extends TextureSheetParticle {
    private static final double DT = 0.05D;
    private static final double DRAG = 1.5D;

    private final double random1;
    private double vx;
    private double vy;
    private double vz;

    protected BacteriaStepParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z);
        this.random1 = this.random.nextDouble();
        double random2 = this.random.nextDouble();

        this.lifetime = Math.max(1, Mth.ceil((0.5D + this.random.nextDouble() * 3.5D) / DT));
        double dx = this.random.nextDouble() - 0.5D;
        double dy = 0.5D;
        double dz = this.random.nextDouble() - 0.5D;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double speed = 0.1D + random2 * 0.6D;
        this.vx = dx / length * speed;
        this.vy = dy / length * speed;
        this.vz = dz / length * speed;

        this.quadSize = (float) (0.02D + this.random1 * 0.02D);
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.rCol = 0.0F;
        this.gCol = 0.0F;
        this.bCol = 0.0F;
        this.alpha = 0.0F;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        double ageSeconds = this.age * DT;
        double ax = Math.cos(Math.toRadians((this.random1 + ageSeconds) * 200.0D)) * 0.2D;
        double ay = 0.3D;
        this.vx += (ax - DRAG * this.vx) * DT;
        this.vy += (ay - DRAG * this.vy) * DT;
        this.vz += (0.0D - DRAG * this.vz) * DT;
        this.setPos(this.x + this.vx * DT, this.y + this.vy * DT, this.z + this.vz * DT);

        float t = (float) this.age / (float) this.lifetime;
        if (t < 0.12F) {
            this.alpha = t / 0.12F;
        } else if (t <= 0.43F) {
            this.alpha = 1.0F;
        } else {
            this.alpha = Math.max(0.0F, 1.0F - (t - 0.43F) / 0.57F);
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z, double xd, double yd, double zd) {
            return new BacteriaStepParticle(level, x, y, z, this.sprites);
        }
    }
}
