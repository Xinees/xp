package com.backroomsport.entity;

import com.backroomsport.events.BacteriaSpawner;
import com.backroomsport.registry.ModSounds;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class BacteriaEntity extends Monster implements GeoEntity {
    // ---- phases (same idea as xp_backrooms phases in the Bedrock script) ----
    public static final int STATE_IDLE = 0;
    public static final int STATE_TRIGGERED = 1;
    public static final int STATE_CHASING = 2;
    public static final int STATE_SEARCHING = 3;
    public static final int STATE_KILLING = 4;

    // ---- numbers taken from the Bedrock addon ----
    // Real speeds in blocks per second. In Bedrock the addon animations are authored with
    // anim_time = distance / 3.66 (walking) and distance / 12 (sprinting), i.e. the mob really walks at
    // 3.66 b/s (0.41 * 0.385 stroll) and runs at 12 b/s (0.41 * 1.25 melee). The burst (0.65) and the
    // search (0.35) values keep the same ratio. A Java mob moves at 43.17 * attribute^2 blocks/s.
    private static final double SPEED_STROLL = 3.66D;
    private static final double SPEED_CHASE = 12.0D;
    private static final double SPEED_BURST = SPEED_CHASE * 0.65D / 0.41D;   // 19.0 b/s for 1 s
    private static final double SPEED_SEARCH = SPEED_STROLL * 0.35D / 0.41D; // 3.1 b/s
    private static final double NAV_MODIFIER = 1.0D;
    // animation speed: Bedrock anim_time_update = query.modified_distance_moved / X
    private static final double WALK_BLOCKS_PER_ANIM_SECOND = 3.66D;
    private static final double SPRINT_BLOCKS_PER_ANIM_SECOND = 12.0D;
    private static final int BURST_TICKS = 20;
    private static final int TRIGGER_TICKS = 15;           // 0.75 s freeze before the chase
    private static final int SEARCH_TICKS = 1200;          // 60 s
    private static final double SEARCH_RADIUS = 30.0D;
    private static final double RANGE_SNEAK = 12.5D;       // needs line of sight
    private static final double RANGE_WALK = 32.0D;        // needs line of sight
    private static final double RANGE_SPRINT = 48.0D;      // no line of sight needed
    private static final double LOSE_DISTANCE = 64.0D;
    private static final int LOSE_SIGHT_TICKS = 60;
    private static final int KILL_TICKS = 40;              // 2 s of the grab
    private static final int ATTACK_COOLDOWN = 20;
    private static final int DESPAWN_AGE = 3600;           // idle and far from players

    private static final EntityDataAccessor<Integer> STATE =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> GRABBED_ID =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.INT);

    private static final String PREFIX = "animation.xp_backrooms.bacteria.";
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop(PREFIX + "walking");
    private static final RawAnimation ANIM_SPRINT_LEGS = RawAnimation.begin().thenLoop(PREFIX + "sprinting_legs");
    private static final RawAnimation ANIM_SPRINT_ARMS = RawAnimation.begin().thenLoop(PREFIX + "sprinting_arms");
    private static final RawAnimation ANIM_ATTACK = RawAnimation.begin().thenPlay(PREFIX + "sprinting_arms_attack");
    private static final RawAnimation ANIM_TRIGGERED = RawAnimation.begin().thenPlayAndHold(PREFIX + "triggered");
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop(PREFIX + "twitching_overlay");
    private static final RawAnimation ANIM_SEARCH = RawAnimation.begin().thenLoop(PREFIX + "searching_overlay");
    private static final RawAnimation ANIM_KILL = RawAnimation.begin().thenPlayAndHold(PREFIX + "kill");
    private static final RawAnimation ANIM_KILL_OVERLAY = RawAnimation.begin().thenPlayAndHold(PREFIX + "kill.overlay");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private double groundSpeed = 0.0D;
    private double smoothSpeed = 0.0D;
    private int stillTicks = 100;

    // server side behaviour state (not saved: after a reload the mob simply starts idle)
    private UUID targetId;
    private Vec3 lastSeen;
    private int phaseTicks;
    private int burstTicks;
    private int attackCooldown;
    private int noSightTicks;
    private int killTicks;
    private int screamTimer;
    private int screamLeft;
    private int wailDelay = -1;

    public BacteriaEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, attributeFor(SPEED_STROLL))
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D);
    }

    // ================================================================== data

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STATE, STATE_IDLE);
        this.entityData.define(GRABBED_ID, -1);
    }

    public int getState() {
        return this.entityData.get(STATE);
    }

    public void setState(int state) {
        this.entityData.set(STATE, state);
    }

    /** Entity id of the player that is being grabbed, or -1. Used by the client to lock input and camera. */
    public int getGrabbedPlayerId() {
        return this.entityData.get(GRABBED_ID);
    }

    public void scheduleSpawnWail() {
        this.wailDelay = 20;
    }

    // ================================================================== goals (idle only)

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(5, new IdleStrollGoal(this, NAV_MODIFIER));
        this.goalSelector.addGoal(6, new IdleLookGoal(this));
    }

    private static class IdleStrollGoal extends WaterAvoidingRandomStrollGoal {
        private final BacteriaEntity bacteria;

        IdleStrollGoal(BacteriaEntity mob, double speed) {
            super(mob, speed);
            this.bacteria = mob;
        }

        @Override
        public boolean canUse() {
            return bacteria.getState() == STATE_IDLE && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return bacteria.getState() == STATE_IDLE && super.canContinueToUse();
        }
    }

    private static class IdleLookGoal extends RandomLookAroundGoal {
        private final BacteriaEntity bacteria;

        IdleLookGoal(BacteriaEntity mob) {
            super(mob);
            this.bacteria = mob;
        }

        @Override
        public boolean canUse() {
            return bacteria.getState() == STATE_IDLE && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return bacteria.getState() == STATE_IDLE && super.canContinueToUse();
        }
    }

    // ================================================================== main tick

    @Override
    public void tick() {
        super.tick();
        double dx = this.getX() - this.xo;
        double dz = this.getZ() - this.zo;
        this.groundSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0D;
        // smoothed speed drives the animation rate, a short hold keeps the walk cycle from restarting
        this.smoothSpeed += (this.groundSpeed - this.smoothSpeed) * 0.25D;
        if (this.groundSpeed > 0.15D) {
            this.stillTicks = 0;
        } else if (this.stillTicks < 1000) {
            this.stillTicks++;
        }

        if (this.level().isClientSide) {
            return;
        }

        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (this.wailDelay > 0 && --this.wailDelay == 0) {
            this.playSound(ModSounds.BACTERIA_WAILING.get(), 1.0F, 1.0F);
        }

        switch (getState()) {
            case STATE_IDLE -> tickIdle();
            case STATE_TRIGGERED -> tickTriggered();
            case STATE_CHASING -> tickChasing();
            case STATE_SEARCHING -> tickSearching();
            case STATE_KILLING -> tickKilling();
            default -> resetToIdle();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A Java ground mob moves at 43.17 * attribute^2 blocks per second (the forward input is the attribute too). */
    private static double attributeFor(double blocksPerSecond) {
        return Math.sqrt(blocksPerSecond / 43.17D);
    }

    private void setRealSpeed(double blocksPerSecond) {
        setBaseSpeed(attributeFor(blocksPerSecond));
    }

    private void setBaseSpeed(double value) {
        AttributeInstance attr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(value);
        }
    }

    private boolean validTarget(Player p) {
        return p != null && p.isAlive() && !p.isSpectator() && !p.isCreative() && p.level() == this.level();
    }

    private Player getTargetPlayer() {
        return this.targetId == null ? null : this.level().getPlayerByUUID(this.targetId);
    }

    private void faceTowards(Vec3 point) {
        this.lookAt(EntityAnchorArgument.Anchor.EYES, point);
        this.yBodyRot = this.getYRot();
        this.setYHeadRot(this.getYRot());
    }

    /** Vision rules from the Bedrock addon: sneaking 12.5 / walking 32 (both need sight), sprinting 48 (no sight). */
    private Player findTarget() {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : this.level().players()) {
            if (!validTarget(p)) {
                continue;
            }
            double dist = this.distanceTo(p);
            double range;
            boolean needSight;
            if (p.isSprinting()) {
                range = RANGE_SPRINT;
                needSight = false;
            } else if (p.isCrouching()) {
                range = RANGE_SNEAK;
                needSight = true;
            } else {
                range = RANGE_WALK;
                needSight = true;
            }
            if (dist > range) {
                continue;
            }
            if (needSight && !this.hasLineOfSight(p)) {
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    private void resetToIdle() {
        this.setState(STATE_IDLE);
        this.entityData.set(GRABBED_ID, -1);
        this.targetId = null;
        this.lastSeen = null;
        this.phaseTicks = 0;
        this.burstTicks = 0;
        this.noSightTicks = 0;
        this.screamLeft = 0;
        this.screamTimer = 0;
        this.getNavigation().stop();
        this.setRealSpeed(SPEED_STROLL);
    }

    // ------------------------------------------------------------------ idle

    private void tickIdle() {
        if (this.tickCount % 5 == 0) {
            Player p = findTarget();
            if (p != null) {
                startTriggered(p);
                return;
            }
        }
        if (this.tickCount > DESPAWN_AGE && this.tickCount % 200 == 0
                && this.level().getNearestPlayer(this, 55.0D) == null) {
            this.discard();
        }
    }

    // ------------------------------------------------------------------ triggered (freeze and stare)

    private void startTriggered(Player p) {
        this.targetId = p.getUUID();
        this.lastSeen = p.position();
        this.setState(STATE_TRIGGERED);
        this.phaseTicks = TRIGGER_TICKS;
        this.getNavigation().stop();
        this.setBaseSpeed(0.0D);
        this.faceTowards(p.getEyePosition());
        this.playSound(ModSounds.BACTERIA_SPOTTED.get(), 1.0F, 1.0F);
        if (p instanceof ServerPlayer sp && this.tickCount >= 60) {
            BacteriaSpawner.onEncounter(sp);
        }
    }

    private void tickTriggered() {
        Player p = getTargetPlayer();
        if (!validTarget(p)) {
            resetToIdle();
            return;
        }
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.faceTowards(p.getEyePosition());
        if (--this.phaseTicks <= 0) {
            startChase();
        }
    }

    // ------------------------------------------------------------------ chase

    private void startChase() {
        this.setState(STATE_CHASING);
        this.burstTicks = BURST_TICKS;
        this.noSightTicks = 0;
        this.screamLeft = 0;
        this.screamTimer = 20;
        this.setRealSpeed(SPEED_BURST);
    }

    private void tickChasing() {
        Player p = getTargetPlayer();
        if (!validTarget(p) || this.distanceTo(p) > LOSE_DISTANCE) {
            startSearch();
            return;
        }

        if (this.hasLineOfSight(p)) {
            this.lastSeen = p.position();
            this.noSightTicks = 0;
        } else if (++this.noSightTicks > LOSE_SIGHT_TICKS) {
            startSearch();
            return;
        }

        if (this.burstTicks > 0) {
            if (--this.burstTicks == 0) {
                this.setRealSpeed(SPEED_CHASE);
            }
        }

        this.getLookControl().setLookAt(p, 40.0F, 40.0F);

        if (this.tickCount % 4 == 0 || this.getNavigation().isDone()) {
            boolean pathFound = this.getNavigation().moveTo(p, NAV_MODIFIER);
            if (!pathFound) {
                this.getMoveControl().setWantedPosition(p.getX(), p.getY(), p.getZ(), NAV_MODIFIER);
            }
        }
        if (this.horizontalCollision && this.onGround()) {
            this.getJumpControl().jump();
        }

        if (this.tickCount % 3 == 0) {
            breakBlocksAhead();
        }

        tickScreams();

        if (this.attackCooldown <= 0 && this.getBoundingBox().inflate(0.9D, 0.2D, 0.9D).intersects(p.getBoundingBox())) {
            attack(p);
        }
    }

    /** Three screams, 5 ticks apart, then a 60-80 tick pause (same as the Bedrock script). */
    private void tickScreams() {
        if (this.screamTimer-- > 0) {
            return;
        }
        if (this.screamLeft <= 0) {
            this.screamLeft = 3;
        }
        this.playSound(ModSounds.BACTERIA_SCREAM.get(), 0.5F, 1.0F);
        this.screamLeft--;
        this.screamTimer = this.screamLeft > 0 ? 5 : 60 + this.random.nextInt(21);
    }

    /** Smashes whatever stands in the way, like minecraft:break_blocks in the addon. */
    private void breakBlocksAhead() {
        if (!this.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }
        Vec3 dir = Vec3.directionFromRotation(0.0F, this.getYRot());
        AABB box = this.getBoundingBox().inflate(0.2D, 0.0D, 0.2D).move(dir.x * 0.6D, 0.05D, dir.z * 0.6D);
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY - 0.01D, box.maxZ);
        int broken = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (broken >= 16) {
                break;
            }
            BlockState state = this.level().getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (state.getDestroySpeed(this.level(), pos) < 0.0F) {
                continue;
            }
            if (state.getCollisionShape(this.level(), pos).isEmpty() && !state.is(net.minecraft.tags.BlockTags.DOORS)) {
                continue;
            }
            this.level().destroyBlock(pos, false, this);
            broken++;
        }
    }

    // ------------------------------------------------------------------ attack and takedown

    private void attack(Player p) {
        this.attackCooldown = ATTACK_COOLDOWN;
        this.triggerAnim("arms", "attack");
        this.playSound(ModSounds.BACTERIA_ATTACK.get(), 1.0F, 1.0F);

        float damage = switch (this.level().getDifficulty()) {
            case HARD -> 14.0F;
            case NORMAL -> 10.0F;
            default -> 6.0F;
        };

        // A raised shield facing the monster absorbs the hit but loses 36 durability.
        if (p.isBlocking()) {
            Vec3 view = p.getViewVector(1.0F);
            Vec3 toMe = this.position().subtract(p.position());
            Vec3 viewH = new Vec3(view.x, 0.0D, view.z);
            Vec3 toMeH = new Vec3(toMe.x, 0.0D, toMe.z);
            if (viewH.lengthSqr() > 1.0E-4D && toMeH.lengthSqr() > 1.0E-4D
                    && viewH.normalize().dot(toMeH.normalize()) > 0.0D) {
                ItemStack shield = p.getUseItem();
                net.minecraft.world.InteractionHand hand = p.getUsedItemHand();
                shield.hurtAndBreak(36, p, pl -> pl.broadcastBreakEvent(hand));
                return;
            }
        }

        float afterArmor = CombatRules.getDamageAfterAbsorb(damage, (float) p.getArmorValue(),
                (float) p.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        boolean lethal = p.getHealth() + p.getAbsorptionAmount() <= afterArmor;
        if (lethal && p instanceof ServerPlayer sp) {
            startTakedown(sp);
            return;
        }
        p.hurt(this.damageSources().mobAttack(this), damage);
    }

    private void startTakedown(ServerPlayer p) {
        this.setState(STATE_KILLING);
        this.entityData.set(GRABBED_ID, p.getId());
        this.targetId = p.getUUID();
        this.killTicks = KILL_TICKS;
        this.getNavigation().stop();
        this.setBaseSpeed(0.0D);
        this.faceTowards(p.getEyePosition());
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
        this.playSound(ModSounds.BACTERIA_TAKEDOWN.get(), 1.0F, 1.0F);
    }

    private void tickKilling() {
        Entity e = this.level().getEntity(this.getGrabbedPlayerId());
        if (!(e instanceof Player p) || !p.isAlive()) {
            resetToIdle();
            return;
        }
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.faceTowards(p.getEyePosition());
        if (this.killTicks % 4 == 0) {
            p.setDeltaMovement(0.0D, Math.min(0.0D, p.getDeltaMovement().y), 0.0D);
            p.hurtMarked = true;
        }
        if (--this.killTicks <= 0) {
            p.hurt(this.damageSources().genericKill(), Float.MAX_VALUE);
            resetToIdle();
        }
    }

    // ------------------------------------------------------------------ search

    private void startSearch() {
        this.setState(STATE_SEARCHING);
        this.phaseTicks = SEARCH_TICKS;
        this.targetId = null;
        this.setRealSpeed(SPEED_SEARCH);
        if (this.lastSeen != null) {
            this.getNavigation().moveTo(this.lastSeen.x, this.lastSeen.y, this.lastSeen.z, 1.0D);
        }
    }

    private void tickSearching() {
        if (--this.phaseTicks <= 0) {
            resetToIdle();
            return;
        }
        if (this.tickCount % 5 == 0) {
            Player p = findTarget();
            if (p != null) {
                this.targetId = p.getUUID();
                this.lastSeen = p.position();
                if (p instanceof ServerPlayer sp && this.tickCount >= 60) {
                    BacteriaSpawner.onEncounter(sp);
                }
                startChase();
                return;
            }
        }
        if (this.tickCount % 20 == 0 && this.random.nextFloat() < 0.1F) {
            this.playSound(ModSounds.BACTERIA_IDLE.get(), 1.0F, 1.0F);
        }
        if (this.getNavigation().isDone() && this.lastSeen != null) {
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            double radius = this.random.nextDouble() * SEARCH_RADIUS;
            this.getNavigation().moveTo(this.lastSeen.x + Math.cos(angle) * radius, this.lastSeen.y,
                    this.lastSeen.z + Math.sin(angle) * radius, 1.0D);
        }
    }

    // ================================================================== damage rules

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
            if (!this.level().isClientSide) {
                this.discard();
            }
            return true;
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }
        return false;
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void checkDespawn() {
        if (this.level().isClientSide) {
            return;
        }
        if (this.level().getNearestPlayer(this, 200.0D) == null) {
            this.discard();
        }
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    // ================================================================== sounds

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(ModSounds.BACTERIA_WALK.get(), 0.8F, 1.0F);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.BACTERIA_HURT.get();
    }

    // ================================================================== animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementController));
        AnimationController<BacteriaEntity> arms = new AnimationController<>(this, "arms", 4, this::armsController);
        arms.triggerableAnim("attack", ANIM_ATTACK);
        controllers.add(arms);
    }

    private boolean isAnimMoving() {
        return this.stillTicks < 6;
    }

    /** Bedrock: anim_time_update = distance moved / blocksPerAnimSecond -> playback rate = speed / that. */
    private double distanceRate(double blocksPerAnimSecond) {
        return Math.min(4.0D, this.smoothSpeed / blocksPerAnimSecond);
    }

    private PlayState movementController(AnimationState<BacteriaEntity> state) {
        AnimationController<BacteriaEntity> controller = state.getController();
        int phase = getState();
        boolean moving = isAnimMoving();
        controller.setAnimationSpeed(1.0D);

        if (phase == STATE_KILLING) {
            return state.setAndContinue(ANIM_KILL);
        }
        if (phase == STATE_TRIGGERED) {
            return state.setAndContinue(ANIM_TRIGGERED);
        }
        if (moving && phase == STATE_CHASING) {
            controller.setAnimationSpeed(distanceRate(SPRINT_BLOCKS_PER_ANIM_SECOND));
            return state.setAndContinue(ANIM_SPRINT_LEGS);
        }
        if (moving) {
            controller.setAnimationSpeed(distanceRate(WALK_BLOCKS_PER_ANIM_SECOND));
            return state.setAndContinue(ANIM_WALK);
        }
        return state.setAndContinue(ANIM_IDLE);
    }

    private PlayState armsController(AnimationState<BacteriaEntity> state) {
        int phase = getState();
        AnimationController<BacteriaEntity> controller = state.getController();
        controller.setAnimationSpeed(1.0D);
        if (phase == STATE_KILLING) {
            return state.setAndContinue(ANIM_KILL_OVERLAY);
        }
        if (phase == STATE_CHASING && isAnimMoving()) {
            controller.setAnimationSpeed(distanceRate(SPRINT_BLOCKS_PER_ANIM_SECOND));
            return state.setAndContinue(ANIM_SPRINT_ARMS);
        }
        if (phase == STATE_SEARCHING) {
            return state.setAndContinue(ANIM_SEARCH);
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
