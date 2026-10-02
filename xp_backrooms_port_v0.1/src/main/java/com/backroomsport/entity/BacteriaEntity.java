package com.backroomsport.entity;

import com.backroomsport.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

public class BacteriaEntity extends Monster implements GeoEntity {
    // Behaviour phases (same names as the Bedrock addon's xp_backrooms:entity_state)
    public static final int STATE_IDLE = 0;
    public static final int STATE_TRIGGERED = 1;
    public static final int STATE_CHASING = 2;
    public static final int STATE_SEARCHING = 3;

    private static final EntityDataAccessor<Integer> STATE =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.INT);

    private static final String PREFIX = "animation.xp_backrooms.bacteria.";
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop(PREFIX + "walking");
    private static final RawAnimation ANIM_SPRINT_LEGS = RawAnimation.begin().thenLoop(PREFIX + "sprinting_legs");
    private static final RawAnimation ANIM_SPRINT_ARMS = RawAnimation.begin().thenLoop(PREFIX + "sprinting_arms");
    private static final RawAnimation ANIM_TRIGGERED = RawAnimation.begin().thenPlayAndHold(PREFIX + "triggered");
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop(PREFIX + "twitching_overlay");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Horizontal speed in blocks per second, measured every tick (client and server). */
    private double groundSpeed = 0.0D;

    public BacteriaEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.41D)
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D);
    }

    // ------------------------------------------------------------------ data

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STATE, STATE_IDLE);
    }

    public int getState() {
        return this.entityData.get(STATE);
    }

    public void setState(int state) {
        this.entityData.set(STATE, state);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("State", getState());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("State")) {
            setState(tag.getInt("State"));
        }
    }

    // ------------------------------------------------------------------ behaviour (placeholder)

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.385D));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        double dx = this.getX() - this.xo;
        double dz = this.getZ() - this.zo;
        this.groundSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0D;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
            this.discard();
            return true;
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    // ------------------------------------------------------------------ sounds

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(ModSounds.BACTERIA_WALK.get(), 0.8F, 1.0F);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.BACTERIA_HURT.get();
    }

    // ------------------------------------------------------------------ animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementController));
        controllers.add(new AnimationController<>(this, "arms", 4, this::armsController));
    }

    private PlayState movementController(AnimationState<BacteriaEntity> state) {
        AnimationController<BacteriaEntity> controller = state.getController();
        int phase = getState();
        boolean moving = this.groundSpeed > 0.1D;

        if (phase == STATE_TRIGGERED) {
            controller.setAnimationSpeed(1.0D);
            return state.setAndContinue(ANIM_TRIGGERED);
        }
        if (moving && phase == STATE_CHASING) {
            // Bedrock: anim_time = distance_moved / 12
            controller.setAnimationSpeed(Math.max(0.2D, this.groundSpeed / 12.0D));
            return state.setAndContinue(ANIM_SPRINT_LEGS);
        }
        if (moving) {
            // Bedrock: anim_time = distance_moved / 3.66
            controller.setAnimationSpeed(Math.max(0.2D, this.groundSpeed / 3.66D));
            return state.setAndContinue(ANIM_WALK);
        }
        controller.setAnimationSpeed(1.0D);
        return state.setAndContinue(ANIM_IDLE);
    }

    private PlayState armsController(AnimationState<BacteriaEntity> state) {
        if (getState() == STATE_CHASING && this.groundSpeed > 0.1D) {
            state.getController().setAnimationSpeed(Math.max(0.2D, this.groundSpeed / 12.0D));
            return state.setAndContinue(ANIM_SPRINT_ARMS);
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
