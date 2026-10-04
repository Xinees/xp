package com.backroomsport.entity;

import com.backroomsport.events.BacteriaSpawner;
import com.backroomsport.registry.ModParticles;
import com.backroomsport.registry.ModSounds;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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
    // Bedrock speeds: movement 0.41 (stroll multiplier 0.385, chase multiplier 1.25), burst 0.65, search 0.35.
    // BLOCKS_PER_SECOND_PER_UNIT converts a Bedrock movement value into real blocks per second.
    // This single number is the only "feel" setting: raise it and the monster is faster, lower and it is slower.
    // The animation tempo follows the real speed automatically, so feet never slide.
    private static final double BLOCKS_PER_SECOND_PER_UNIT = 12.0D;
    private static final double SPEED_STROLL = 0.41D * 0.385D * BLOCKS_PER_SECOND_PER_UNIT;
    private static final double SPEED_CHASE = 0.41D * 1.25D * BLOCKS_PER_SECOND_PER_UNIT;
    private static final double SPEED_BURST = 0.65D * 1.25D * BLOCKS_PER_SECOND_PER_UNIT;
    private static final double SPEED_SEARCH = 0.35D * 0.385D * BLOCKS_PER_SECOND_PER_UNIT;
    private static final double NAV_MODIFIER = 1.0D;
    // Bedrock: anim_time_update = query.modified_distance_moved / X (X = 3.66 walking, 12 sprinting).
    // modified_distance_moved is the limb-swing distance: 4 units per block, at most 1 unit per tick.
    private static final double MODIFIED_DISTANCE_PER_BLOCK = 4.0D;
    private static final double MAX_MODIFIED_DISTANCE_PER_SECOND = 20.0D;
    private static final double WALK_BLOCKS_PER_ANIM_SECOND = 3.66D;
    private static final double SPRINT_BLOCKS_PER_ANIM_SECOND = 12.0D;
    // v.step timeline of the Bedrock animations (animation seconds -> v.step value) and their lengths
    private static final double WALK_LENGTH = 4.0D;
    private static final double SPRINT_LENGTH = 1.5D;
    private static final double[] WALK_STEP_TIMES = {0.8333D, 2.0D, 2.8333D, 3.98D};
    private static final int[] WALK_STEP_VALUES = {0, 1, 0, 1};
    private static final double[] SPRINT_STEP_TIMES = {0.09D, 0.465D, 0.84D, 1.215D};
    private static final int[] SPRINT_STEP_VALUES = {0, 1, 0, 1};
    private static boolean instructionEventsWork = false;
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
    private static final EntityDataAccessor<Float> SPEED_TARGET =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.FLOAT);

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
    private int stillTicks = 100;
    // client side step bookkeeping (the "v.step" variable of the Bedrock animation controller)
    private int stepState = 0;
    private int fbMode = 0;
    private double fbTime = 0.0D;
    private int fbMovingTicks = 0;

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
        this.noCulling = true; // Bedrock: should_update_bones_and_effects_offscreen = true
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
        this.entityData.define(SPEED_TARGET, (float) SPEED_STROLL);
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
        // a short hold keeps the walk cycle from restarting on tiny stops
        if (this.groundSpeed > 0.15D) {
            this.stillTicks = 0;
        } else if (this.stillTicks < 1000) {
            this.stillTicks++;
        }

        if (this.level().isClientSide) {
            clientFallbackSteps();
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
        this.entityData.set(SPEED_TARGET, (float) blocksPerSecond);
    }

    private void setStill() {
        setBaseSpeed(0.0D);
        this.entityData.set(SPEED_TARGET, 0.0F);
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
        this.setStill();
        this.faceTowards(p.getEyePosition());
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
        this.setStill();
        this.faceTowards(p.getEyePosition());
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
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
        p.setDeltaMovement(0.0D, Math.min(0.0D, p.getDeltaMovement().y), 0.0D);
        p.hurtMarked = true;
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
    public boolean isPushable() {
        return getState() != STATE_KILLING && super.isPushable();
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
        // footsteps are played from the animation timeline (see onStepInstruction), like Bedrock's step_effects
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.BACTERIA_HURT.get();
    }

    // ================================================================== animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        AnimationController<BacteriaEntity> movement = new AnimationController<>(this, "movement", 4, this::movementController);
        movement.setCustomInstructionKeyframeHandler(event -> onStepInstruction(event.getKeyframeData().getInstructions()));
        movement.setSoundKeyframeHandler(event -> onSoundKeyframe(event.getKeyframeData().getSound()));
        controllers.add(movement);

        AnimationController<BacteriaEntity> arms = new AnimationController<>(this, "arms", 4, this::armsController);
        arms.triggerableAnim("attack", ANIM_ATTACK);
        arms.setSoundKeyframeHandler(event -> onSoundKeyframe(event.getKeyframeData().getSound()));
        controllers.add(arms);
    }

    private boolean isAnimMoving() {
        return this.stillTicks < 6;
    }

    /** 0 = no leg animation, 1 = walking, 2 = sprinting (same conditions as the Bedrock movement controller). */
    private int legMode() {
        int phase = getState();
        if (phase == STATE_KILLING || phase == STATE_TRIGGERED || !isAnimMoving()) {
            return 0;
        }
        return phase == STATE_CHASING ? 2 : 1;
    }

    /** Bedrock: anim_time = modified_distance_moved / X. Playback rate = limb distance per second / X. */
    private double animRate(int mode) {
        double blocksPerSecond = this.entityData.get(SPEED_TARGET);
        double limb = Math.min(MODIFIED_DISTANCE_PER_BLOCK * blocksPerSecond, MAX_MODIFIED_DISTANCE_PER_SECOND);
        return limb / (mode == 2 ? SPRINT_BLOCKS_PER_ANIM_SECOND : WALK_BLOCKS_PER_ANIM_SECOND);
    }

    // ------------------------------------------------------------------ step effects (client)

    private void onStepInstruction(String instructions) {
        if (instructions == null) {
            return;
        }
        instructionEventsWork = true;
        String text = instructions.replace(" ", "");
        if (text.contains("v.step=0")) {
            stepEvent(0);
        } else if (text.contains("v.step=1")) {
            stepEvent(1);
        }
    }

    /** Bedrock step_effects controller: the left/right state changes only when v.step changes. */
    private void stepEvent(int side) {
        if (side == this.stepState) {
            return;
        }
        this.stepState = side;
        playStepEffects(side == 0);
    }

    private void playStepEffects(boolean left) {
        Level level = this.level();
        if (!level.isClientSide) {
            return;
        }
        // locators step_left [5, 0.25, -0.35] and step_right [-4, 0.25, 0] (pixels, model space)
        double lx = (left ? 5.0D : -4.0D) / 16.0D;
        double lz = (left ? -0.35D : 0.0D) / 16.0D;
        double yaw = Math.toRadians(this.yBodyRot);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double wx = this.getX() + cos * lx + sin * lz;
        double wz = this.getZ() + sin * lx - cos * lz;
        double wy = this.getY() + 0.25D / 16.0D;

        level.playLocalSound(this.getX(), this.getY(), this.getZ(), ModSounds.BACTERIA_WALK.get(),
                SoundSource.HOSTILE, 1.0F, 1.0F, false);
        int count = 4 + level.random.nextInt(5); // math.random_integer(4, 8)
        for (int i = 0; i < count; i++) {
            level.addParticle(ModParticles.BACTERIA_STEP.get(), wx, wy, wz, 0.0D, 0.0D, 0.0D);
        }
    }

    /** Sound keyframes of the Bedrock animations: spotted (triggered), attack (0.25 s into the swing), takedown (kill). */
    private void onSoundKeyframe(String effect) {
        if (effect == null || !this.level().isClientSide) {
            return;
        }
        SoundEvent sound = switch (effect) {
            case "spotted" -> ModSounds.BACTERIA_SPOTTED.get();
            case "attack" -> ModSounds.BACTERIA_ATTACK.get();
            case "takedown" -> ModSounds.BACTERIA_TAKEDOWN.get();
            default -> null;
        };
        if (sound != null) {
            this.level().playLocalSound(this.getX(), this.getY(), this.getZ(), sound, SoundSource.HOSTILE, 1.0F, 1.0F, false);
        }
    }

    /**
     * Safety net: if the GeckoLib build ignores the "timeline" instructions, replay the same v.step timeline from
     * a local clock that follows the animation rate. It never runs once a real keyframe event has been received.
     */
    private void clientFallbackSteps() {
        if (instructionEventsWork) {
            return;
        }
        int mode = legMode();
        if (mode != this.fbMode) {
            this.fbMode = mode;
            this.fbTime = -4.0D / 20.0D; // the 4 tick blend before the animation really starts
            this.fbMovingTicks = 0;
        }
        if (mode == 0) {
            return;
        }
        this.fbMovingTicks++;
        double length = mode == 2 ? SPRINT_LENGTH : WALK_LENGTH;
        double[] times = mode == 2 ? SPRINT_STEP_TIMES : WALK_STEP_TIMES;
        int[] values = mode == 2 ? SPRINT_STEP_VALUES : WALK_STEP_VALUES;
        double previous = this.fbTime;
        this.fbTime += animRate(mode) / 20.0D;
        if (this.fbMovingTicks < 80) {
            return;
        }
        for (int i = 0; i < times.length; i++) {
            if (Math.floor((this.fbTime - times[i]) / length) > Math.floor((previous - times[i]) / length)) {
                stepEvent(values[i]);
            }
        }
    }

    private PlayState movementController(AnimationState<BacteriaEntity> state) {
        AnimationController<BacteriaEntity> controller = state.getController();
        int phase = getState();
        int mode = legMode();
        controller.setAnimationSpeed(1.0D);

        if (phase == STATE_KILLING) {
            return state.setAndContinue(ANIM_KILL);
        }
        if (phase == STATE_TRIGGERED) {
            return state.setAndContinue(ANIM_TRIGGERED);
        }
        if (mode == 2) {
            controller.setAnimationSpeed(animRate(2));
            return state.setAndContinue(ANIM_SPRINT_LEGS);
        }
        if (mode == 1) {
            controller.setAnimationSpeed(animRate(1));
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
        if (legMode() == 2) {
            controller.setAnimationSpeed(animRate(2));
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
