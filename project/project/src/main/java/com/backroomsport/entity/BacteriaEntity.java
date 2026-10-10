package com.backroomsport.entity;

import com.backroomsport.XpBackrooms;
import com.backroomsport.events.BacteriaSpawner;
import com.backroomsport.registry.ModParticles;
import com.backroomsport.registry.ModSounds;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import net.minecraftforge.registries.ForgeRegistries;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

// Base: version 0.4 (xp_backrooms-0_4.jar). Everything that is new in 0.5 is marked with "0.5" in a comment:
// no jumping, the walking animation tempo, and walking / sprinting / attack play baked copies of the addon animations
// (bacteria_runtime.animation.json) because GeckoLib cannot do "relative_to": "entity" and additive layers.
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
    private static final int BURST_TICKS = 20;             // 1 s of burst speed after the spotting
    private static final int TRIGGER_TICKS = 15;           // 0.75 s freeze before the chase
    private static final int SEARCH_TICKS = 1200;          // 60 s
    private static final double SEARCH_RADIUS = 30.0D;
    private static final double RANGE_SNEAK = 12.5D;       // needs line of sight
    private static final double RANGE_WALK = 32.0D;        // needs line of sight
    private static final double RANGE_SPRINT = 48.0D;      // no line of sight needed
    private static final double LOSE_DISTANCE = 64.0D;
    private static final int TRACK_TIMEOUT_TICKS = 160;   // 8 s without seeing or hearing the player: stop tracking, start searching
    private static final int KILL_TICKS = 37;              // Bedrock: duration 2 s * 0.925
    // Distance from the monster to the player during the grab. The Bedrock script uses 1.15; 0.9 fills the screen more.
    private static final double GRAB_DISTANCE = 0.9D;
    private static final float GRAB_PITCH = -10.0F;        // viewOffsets.headRotation x (looking slightly up)
    private static final int ATTACK_ANIM_TICKS = 34;       // 1.5 s animation + 4 tick blend
    private static final int ATTACK_COOLDOWN = 20;
    private static final int DESPAWN_AGE = 3600;           // idle and far from players
    private static final double DESPAWN_DISTANCE = 96.0D;  // an old idle mob only disappears when no player is this close

    // ---- brain tuning (AI upgrade) ----
    // Maximum body turn per tick in degrees. Lower = wider, smoother turns. The mob also slows down in sharp corners.
    private static final float TURN_IDLE = 14.0F;
    private static final float TURN_SEARCH = 20.0F;
    private static final float TURN_CHASE = 32.0F;
    private static final float TURN_BURST = 42.0F;
    // While idle the mob sometimes wanders in the general direction of the nearest player (a slow stalk).
    private static final boolean STALK_PLAYERS = true;
    private static final double STALK_RANGE = 96.0D;
    private static final float STALK_CHANCE = 0.3F;

    private static final EntityDataAccessor<Integer> STATE =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> GRABBED_ID =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTACK_SERIAL =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SPEED_TARGET =
            SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.FLOAT);

    private static final String PREFIX = "animation.xp_backrooms.bacteria.";
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop(PREFIX + "walking");
    private static final RawAnimation ANIM_SPRINT_LEGS = RawAnimation.begin().thenLoop(PREFIX + "sprinting_legs");
    private static final RawAnimation ANIM_SPRINT_ARMS = RawAnimation.begin().thenLoop(PREFIX + "sprinting_arms");
    // Two raw animations that differ only by loop type, so every new attack restarts the swing.
    private static final RawAnimation ANIM_ATTACK_A = RawAnimation.begin().thenPlay(PREFIX + "sprinting_arms_attack");
    private static final RawAnimation ANIM_ATTACK_B = RawAnimation.begin().thenPlayAndHold(PREFIX + "sprinting_arms_attack");
    private static final RawAnimation ANIM_TRIGGERED = RawAnimation.begin().thenPlayAndHold(PREFIX + "triggered");
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop(PREFIX + "twitching_overlay");
    private static final RawAnimation ANIM_SEARCH = RawAnimation.begin().thenLoop(PREFIX + "searching_overlay");

    // 0.5: GeckoLib does not know "relative_to": "entity", which the addon uses for the arm_offset and hand_offset bones
    // of walking, sprinting_arms and sprinting_arms_attack. Played as they are, the arms and hands are turned by every
    // rotation of their parents (up to ~140 degrees off), which is the arm swinging. The baked_* animations in
    // bacteria.animation.json are those animations with that rotation converted into plain parent relative values,
    // already added together the way the addon's controllers add them.
    // true: like the addon, walking / sprinting play together with twitching_overlay (and searching_overlay);
    // false: only walking / sprinting (the calm look of the animation preview in Blockbench).
    private static final boolean BEDROCK_LAYERS = true;
    // The baked_walking* animations are recorded at the tempo of the stroll speed (the addon formula
    // modified_distance_moved / 3.66), baked_sprint* at the sprint tempo (capped, so the same for chase and burst).
    // The walking controller speed is the ratio to that recorded tempo (1.0 while strolling).
    private static final double WALK_BAKED_RATE = MODIFIED_DISTANCE_PER_BLOCK * SPEED_STROLL / WALK_BLOCKS_PER_ANIM_SECOND;
    private static final RawAnimation ANIM_BAKED_WALK = RawAnimation.begin().thenLoop(PREFIX + "baked_walking");
    private static final RawAnimation ANIM_BAKED_WALK_TW = RawAnimation.begin().thenLoop(PREFIX + "baked_walking_tw");
    private static final RawAnimation ANIM_BAKED_WALK_TW_SEARCH = RawAnimation.begin().thenLoop(PREFIX + "baked_walking_tw_search");
    private static final RawAnimation ANIM_BAKED_SPRINT = RawAnimation.begin().thenLoop(PREFIX + "baked_sprint");
    private static final RawAnimation ANIM_BAKED_SPRINT_TW = RawAnimation.begin().thenLoop(PREFIX + "baked_sprint_tw");
    // two raw animations that differ only by loop type, so every new attack restarts the swing
    private static final RawAnimation ANIM_BAKED_ATTACK_RUN_A = RawAnimation.begin().thenPlay(PREFIX + "baked_sprint_attack");
    private static final RawAnimation ANIM_BAKED_ATTACK_RUN_B = RawAnimation.begin().thenPlayAndHold(PREFIX + "baked_sprint_attack");
    private static final RawAnimation ANIM_BAKED_ATTACK_STILL_A = RawAnimation.begin().thenPlay(PREFIX + "baked_attack_still");
    private static final RawAnimation ANIM_BAKED_ATTACK_STILL_B = RawAnimation.begin().thenPlayAndHold(PREFIX + "baked_attack_still");
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
    private Vec3 grabPos;
    private float grabYaw;
    // client side attack animation bookkeeping
    private int lastAttackSerial = Integer.MIN_VALUE;
    private int attackAnimTicks = 0;
    private int clientKillTicks = 0;
    private int screamTimer;
    private int screamLeft;
    private int wailDelay = -1;

    // brain state (server side, not saved)
    private Vec3 lastSeenVel = Vec3.ZERO;      // where the player was heading when last seen/heard (blocks per tick)
    private Vec3 pathGoal;                     // the goal the current path was built for
    private int nextRepathTick;
    private Vec3 wanderTarget;
    private int wanderPause = 40;
    private Vec3 searchOrigin;
    private Vec3 searchTarget;
    private int searchPause;
    private int searchStage;
    private int stuckTicks;
    private int stuckLevel;
    private Vec3 stuckAnchor;
    private int sidestepTicks;
    private Vec3 sidestepDir = Vec3.ZERO;
    private final Deque<Vec3> wanderMemory = new ArrayDeque<>();
    private final Deque<Vec3> searchMemory = new ArrayDeque<>();

    public BacteriaEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        this.noCulling = true; // Bedrock: should_update_bones_and_effects_offscreen = true
        this.moveControl = new BacteriaMoveControl(this);
        this.jumpControl = new NoJumpControl(this); // 0.5: the Backrooms have no ledges, the monster never jumps
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, attributeFor(SPEED_STROLL))
                .add(Attributes.FOLLOW_RANGE, 96.0D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D);
    }

    // ================================================================== data

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STATE, STATE_IDLE);
        this.entityData.define(GRABBED_ID, -1);
        this.entityData.define(ATTACK_SERIAL, 0);
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

    // ================================================================== goals and movement

    @Override
    protected void registerGoals() {
        // All walking is driven by the brain below (tickWander / tickChasing / tickSearching).
        // Only the idle head movement stays a vanilla goal.
        this.goalSelector.addGoal(6, new IdleLookGoal(this));
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

    /**
     * Vanilla ground movement turns a mob by up to 90 degrees per tick, which looks like snapping around.
     * This control lets the vanilla logic run, then limits the turn per tick and slows the mob down in sharp corners.
     */
    private static class BacteriaMoveControl extends MoveControl {
        float maxTurn = TURN_IDLE;

        BacteriaMoveControl(BacteriaEntity mob) {
            super(mob);
        }

        @Override
        public void tick() {
            boolean moving = this.operation == MoveControl.Operation.MOVE_TO;
            float before = this.mob.getYRot();
            float error = 0.0F;
            if (moving) {
                double dx = this.wantedX - this.mob.getX();
                double dz = this.wantedZ - this.mob.getZ();
                if (dx * dx + dz * dz > 2.5E-7D) {
                    float target = (float) (Mth.atan2(dz, dx) * 57.2957763671875D) - 90.0F;
                    error = Mth.wrapDegrees(target - before);
                }
            }
            super.tick();
            if (!moving || error == 0.0F) {
                return;
            }
            float step = Mth.clamp(error, -this.maxTurn, this.maxTurn);
            this.mob.setYRot(before + step);
            float abs = Math.abs(error);
            if (abs > 20.0F) {
                float brake = Mth.clamp(1.0F - (abs - 20.0F) / 100.0F, 0.25F, 1.0F);
                this.mob.setSpeed(this.mob.getSpeed() * brake);
            }
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
            int serial = this.entityData.get(ATTACK_SERIAL);
            if (this.lastAttackSerial == Integer.MIN_VALUE) {
                this.lastAttackSerial = serial;
            } else if (serial != this.lastAttackSerial) {
                this.lastAttackSerial = serial;
                this.attackAnimTicks = ATTACK_ANIM_TICKS;
            }
            if (this.attackAnimTicks > 0) {
                this.attackAnimTicks--;
            }
            this.clientKillTicks = getState() == STATE_KILLING ? this.clientKillTicks + 1 : 0;
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

    /** 0.5: swallows every jump request (vanilla movement, pathfinding and the code below), so the monster never hops. */
    private static class NoJumpControl extends JumpControl {
        NoJumpControl(BacteriaEntity mob) {
            super(mob);
        }

        @Override
        public void jump() {
        }
    }

    // ------------------------------------------------------------------ brain helpers

    private void setTurn(float degreesPerTick) {
        if (this.moveControl instanceof BacteriaMoveControl control) {
            control.maxTurn = degreesPerTick;
        }
    }

    private double horizSqr(Vec3 point) {
        double dx = point.x - this.getX();
        double dz = point.z - this.getZ();
        return dx * dx + dz * dz;
    }

    private static float yawTo(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * 57.2957763671875D) - 90.0F;
    }

    /** Turns body and head towards a yaw, at most maxStep degrees per call. */
    private void turnBodyTo(float targetYaw, float maxStep) {
        float diff = Mth.wrapDegrees(targetYaw - this.getYRot());
        float yaw = this.getYRot() + Mth.clamp(diff, -maxStep, maxStep);
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.setYHeadRot(yaw);
    }

    private static void remember(Deque<Vec3> memory, Vec3 spot) {
        memory.addLast(spot);
        while (memory.size() > 10) {
            memory.pollFirst();
        }
    }

    private void resetStuck() {
        this.stuckTicks = 0;
        this.stuckLevel = 0;
        this.stuckAnchor = null;
        this.sidestepTicks = 0;
    }

    /** A spot with a solid floor and three free blocks above it in the given column (near refY), or null. */
    private BlockPos findStandPos(double x, double z, int refY) {
        Level level = this.level();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int dy = 2; dy >= -5; dy--) {
            BlockPos feet = new BlockPos(bx, refY + dy, bz);
            if (!level.hasChunkAt(feet)) {
                return null;
            }
            BlockPos below = feet.below();
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                continue;
            }
            boolean free = true;
            for (int h = 0; h < 3; h++) {
                BlockPos part = feet.above(h);
                if (!level.getBlockState(part).getCollisionShape(level, part).isEmpty()) {
                    free = false;
                    break;
                }
            }
            if (free) {
                return feet;
            }
        }
        return null;
    }

    private record Spot(BlockPos pos, double score) {
    }

    /**
     * Picks a reachable walking target inside a cone around baseAngle (radians in the x/z plane).
     * Places that were not visited recently score higher; an optional leash keeps the target near a point.
     */
    private Vec3 pickWaypoint(double baseAngle, double halfAngle, double minDist, double maxDist,
                              Deque<Vec3> memory, Vec3 leash, double leashRadius) {
        List<Spot> spots = new ArrayList<>();
        int refY = this.blockPosition().getY();
        for (int i = 0; i < 14; i++) {
            double angle = baseAngle + (this.random.nextDouble() * 2.0D - 1.0D) * halfAngle;
            double dist = minDist + this.random.nextDouble() * (maxDist - minDist);
            double x = this.getX() + Math.cos(angle) * dist;
            double z = this.getZ() + Math.sin(angle) * dist;
            if (leash != null) {
                double lx = x - leash.x;
                double lz = z - leash.z;
                if (lx * lx + lz * lz > leashRadius * leashRadius) {
                    continue;
                }
            }
            BlockPos pos = findStandPos(x, z, refY);
            if (pos == null) {
                continue;
            }
            double nearest = 20.0D;
            for (Vec3 old : memory) {
                double mx = old.x - x;
                double mz = old.z - z;
                nearest = Math.min(nearest, Math.sqrt(mx * mx + mz * mz));
            }
            spots.add(new Spot(pos, nearest / 20.0D + this.random.nextDouble() * 0.3D));
        }
        spots.sort((a, b) -> Double.compare(b.score(), a.score()));
        int checked = 0;
        for (Spot spot : spots) {
            if (checked++ >= 4) {
                break;
            }
            Path path = this.getNavigation().createPath(spot.pos(), 0);
            if (path != null && path.canReach()) {
                return Vec3.atBottomCenterOf(spot.pos());
            }
        }
        return null;
    }

    /**
     * Call every tick while the mob is meant to be walking. Notices when it makes no progress and tries, in order:
     * a new path, a sidestep, smashing what is in front of it. Returns true when nothing helped and the
     * current goal should be dropped.
     */
    private boolean updateStuck(boolean shouldMove) {
        if (!shouldMove) {
            this.stuckTicks = 0;
            this.stuckLevel = 0;
            this.stuckAnchor = null;
            return false;
        }
        if (this.stuckAnchor == null) {
            this.stuckAnchor = this.position();
            this.stuckTicks = 0;
            return false;
        }
        if (++this.stuckTicks < 14) {
            return false;
        }
        this.stuckTicks = 0;
        double moved = Math.sqrt(horizSqr(this.stuckAnchor));
        this.stuckAnchor = this.position();
        double expected = this.entityData.get(SPEED_TARGET) * 14.0D / 20.0D;
        if (moved >= Math.max(0.25D, expected * 0.3D)) {
            this.stuckLevel = 0;
            return false;
        }
        this.stuckLevel++;
        switch (this.stuckLevel) {
            case 1 -> {
                this.getNavigation().stop();
                this.nextRepathTick = 0;
            }
            case 2 -> startSidestep();
            case 3 -> {
                breakBlocksAhead();
            }
            default -> {
                this.stuckLevel = 0;
                return true;
            }
        }
        return false;
    }

    private void startSidestep() {
        Vec3 forward = Vec3.directionFromRotation(0.0F, this.getYRot());
        Vec3 left = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 right = new Vec3(forward.z, 0.0D, -forward.x);
        boolean leftFree = this.level().noCollision(this, this.getBoundingBox().move(left.scale(0.8D)));
        boolean rightFree = this.level().noCollision(this, this.getBoundingBox().move(right.scale(0.8D)));
        Vec3 dir;
        if (leftFree && rightFree) {
            dir = this.random.nextBoolean() ? left : right;
        } else if (leftFree) {
            dir = left;
        } else if (rightFree) {
            dir = right;
        } else {
            dir = forward.scale(-1.0D);
        }
        this.sidestepDir = dir;
        this.sidestepTicks = 10;
        this.getNavigation().stop();
    }

    /** Runs a running sidestep. Returns true while it is active (the caller must not steer meanwhile). */
    private boolean tickSidestep() {
        if (this.sidestepTicks <= 0) {
            return false;
        }
        this.sidestepTicks--;
        this.getNavigation().stop();
        this.getMoveControl().setWantedPosition(this.getX() + this.sidestepDir.x * 2.0D, this.getY(),
                this.getZ() + this.sidestepDir.z * 2.0D, NAV_MODIFIER);
        if (this.sidestepTicks == 0) {
            this.nextRepathTick = 0;
        }
        return true;
    }

    /** Follows a goal with a path that is rebuilt only when needed; pushes straight on when there is no path. */
    private void followGoal(Vec3 goal) {
        PathNavigation nav = this.getNavigation();
        boolean due = this.pathGoal == null || this.tickCount >= this.nextRepathTick
                || this.pathGoal.distanceToSqr(goal) > 16.0D;
        if (due) {
            double d = Math.sqrt(this.distanceToSqr(goal));
            this.nextRepathTick = this.tickCount + (d > 16.0D ? 12 : d > 6.0D ? 8 : 4);
            this.pathGoal = goal;
            Path path = nav.createPath(goal.x, goal.y, goal.z, 1);
            if (path != null) {
                nav.moveTo(path, NAV_MODIFIER);
            }
        }
        if (nav.isDone()) {
            // no path, or the path ended short of the goal: push straight on (breaking what can be broken)
            this.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, NAV_MODIFIER);
        }
    }

    private void resetToIdle() {
        this.setState(STATE_IDLE);
        this.entityData.set(GRABBED_ID, -1);
        this.grabPos = null;
        this.targetId = null;
        this.lastSeen = null;
        this.lastSeenVel = Vec3.ZERO;
        this.pathGoal = null;
        this.phaseTicks = 0;
        this.burstTicks = 0;
        this.noSightTicks = 0;
        this.screamLeft = 0;
        this.screamTimer = 0;
        this.wanderTarget = null;
        this.wanderPause = 20 + this.random.nextInt(40);
        this.searchTarget = null;
        this.searchPause = 0;
        resetStuck();
        this.getNavigation().stop();
        this.setRealSpeed(SPEED_STROLL);
        setTurn(TURN_IDLE);
    }

    // ------------------------------------------------------------------ idle (wandering)

    private void tickIdle() {
        if (this.tickCount % 5 == 0) {
            Player p = findTarget();
            if (p != null) {
                startTriggered(p);
                return;
            }
        }
        if (this.tickCount > DESPAWN_AGE && this.tickCount % 200 == 0
                && this.level().getNearestPlayer(this, DESPAWN_DISTANCE) == null) {
            this.discard();
            return;
        }
        tickWander();
    }

    /**
     * The mob walks from waypoint to waypoint, mostly forward so it does not pace back and forth, prefers places it
     * has not visited lately, sometimes stops for a moment, and now and then drifts towards the nearest player.
     */
    private void tickWander() {
        setTurn(TURN_IDLE);
        if (this.wanderPause > 0) {
            this.wanderPause--;
            updateStuck(false);
            return;
        }
        if (tickSidestep()) {
            return;
        }
        if (this.wanderTarget == null) {
            pickWanderTarget();
            return;
        }
        if (this.getNavigation().isDone() || horizSqr(this.wanderTarget) < 2.25D) {
            remember(this.wanderMemory, this.wanderTarget);
            this.wanderTarget = null;
            this.wanderPause = this.random.nextFloat() < 0.45F ? 0 : 30 + this.random.nextInt(80);
            this.getNavigation().stop();
            return;
        }
        if (updateStuck(true)) {
            this.wanderTarget = null;
            this.wanderPause = 15 + this.random.nextInt(20);
            this.getNavigation().stop();
        }
    }

    private void pickWanderTarget() {
        Vec3 heading = Vec3.directionFromRotation(0.0F, this.getYRot());
        double base = Math.atan2(heading.z, heading.x);
        double half = 1.75D;
        double min = 6.0D;
        double max = 20.0D;
        if (STALK_PLAYERS && this.random.nextFloat() < STALK_CHANCE) {
            Player near = this.level().getNearestPlayer(this, STALK_RANGE);
            if (validTarget(near) && this.distanceTo(near) > 24.0F) {
                base = Math.atan2(near.getZ() - this.getZ(), near.getX() - this.getX());
                half = 0.9D;
                min = 10.0D;
                max = 24.0D;
            }
        }
        Vec3 spot = pickWaypoint(base, half, min, max, this.wanderMemory, null, 0.0D);
        if (spot == null) {
            spot = pickWaypoint(base, Math.PI, 4.0D, 14.0D, this.wanderMemory, null, 0.0D);
        }
        if (spot != null && this.getNavigation().moveTo(spot.x, spot.y, spot.z, NAV_MODIFIER)) {
            this.wanderTarget = spot;
            return;
        }
        this.wanderPause = 30 + this.random.nextInt(30);
    }

    // ------------------------------------------------------------------ triggered (freeze and stare)

    private void startTriggered(Player p) {
        this.targetId = p.getUUID();
        this.lastSeen = p.position();
        this.lastSeenVel = Vec3.ZERO;
        this.setState(STATE_TRIGGERED);
        this.phaseTicks = TRIGGER_TICKS;
        this.wanderTarget = null;
        resetStuck();
        this.getNavigation().stop();
        this.setStill();
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
        turnBodyTo(yawTo(p.getX() - this.getX(), p.getZ() - this.getZ()), 38.0F);
        this.getLookControl().setLookAt(p, 60.0F, 60.0F);
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
        this.pathGoal = null;
        this.nextRepathTick = 0;
        this.searchTarget = null;
        resetStuck();
        this.setRealSpeed(SPEED_BURST);
        setTurn(TURN_BURST);
    }

    /**
     * While the player is seen (or heard: a sprinting player is heard through walls, same rule as the spotting range)
     * the mob runs at the player. When contact is lost it does not know where the player is any more: it runs to the
     * last place it saw or heard him, and only starts searching when it gets there or the trail is cold.
     */
    private void tickChasing() {
        Player p = getTargetPlayer();
        if (!validTarget(p) || this.distanceTo(p) > LOSE_DISTANCE) {
            startSearch();
            return;
        }
        double dist = this.distanceTo(p);
        boolean sees = this.hasLineOfSight(p);
        boolean hears = p.isSprinting() && dist <= RANGE_SPRINT;
        boolean contact = sees || hears;
        if (contact) {
            this.lastSeen = p.position();
            this.lastSeenVel = new Vec3(p.getX() - p.xo, 0.0D, p.getZ() - p.zo);
            this.noSightTicks = 0;
        } else {
            this.noSightTicks++;
            if (this.noSightTicks > TRACK_TIMEOUT_TICKS
                    || (this.lastSeen != null && horizSqr(this.lastSeen) < 4.0D)) {
                startSearch();
                return;
            }
        }

        // another player that is clearly closer takes over
        if (this.tickCount % 10 == 0) {
            Player other = findTarget();
            if (other != null && other != p && this.distanceTo(other) + 6.0F < dist) {
                this.targetId = other.getUUID();
                return;
            }
        }

        if (this.burstTicks > 0 && --this.burstTicks == 0) {
            this.setRealSpeed(SPEED_CHASE);
        }
        setTurn(this.burstTicks > 0 ? TURN_BURST : TURN_CHASE);
        if (sees) {
            this.getLookControl().setLookAt(p, 40.0F, 40.0F);
        }

        boolean touching = sees && dist < 2.4D;
        if (!tickSidestep()) {
            if (sees && dist < 3.5D && Math.abs(p.getY() - this.getY()) < 1.7D) {
                // right in front of the player: no path needed, just close in
                this.getNavigation().stop();
                this.getMoveControl().setWantedPosition(p.getX(), p.getY(), p.getZ(), NAV_MODIFIER);
            } else {
                Vec3 goal = contact ? p.position() : (this.lastSeen != null ? this.lastSeen : p.position());
                followGoal(goal);
            }
        }
        if (updateStuck(!touching)) {
            startSearch();
            return;
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

    /** Blocks of this mod (the lobby carpet, wallpaper, ceiling panel and ceiling light) are never smashed. */
    private static boolean isProtectedBlock(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && XpBackrooms.MOD_ID.equals(id.getNamespace());
    }

    /** Smashes whatever stands in the way, like minecraft:break_blocks in the addon (except the lobby blocks). */
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
            if (isProtectedBlock(state)) {
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
        this.entityData.set(ATTACK_SERIAL, this.entityData.get(ATTACK_SERIAL) + 1);

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
        this.setDeltaMovement(Vec3.ZERO);
        this.faceTowards(p.getEyePosition());

        // viewOffsets: offset (0, 0, 1.15) rotated by the monster's yaw, head rotation x = -10, player yaw = monster yaw + 180
        this.grabYaw = this.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0.0F, this.grabYaw);
        this.grabPos = new Vec3(this.getX() + forward.x * GRAB_DISTANCE, this.getY(), this.getZ() + forward.z * GRAB_DISTANCE);
        p.setShiftKeyDown(false);
        p.setSprinting(false);
        lockPlayer(p, true);
        p.playNotifySound(ModSounds.SCREEN_PHASING_INTENSE.get(), SoundSource.MASTER, 1.0F, 1.0F);
    }

    private void lockPlayer(ServerPlayer p, boolean force) {
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
        p.fallDistance = 0.0F;
        if (this.grabPos == null) {
            return;
        }
        if (force || p.position().distanceToSqr(this.grabPos) > 0.0025D) {
            p.connection.teleport(this.grabPos.x, this.grabPos.y, this.grabPos.z,
                    Mth.wrapDegrees(this.grabYaw + 180.0F), GRAB_PITCH);
        }
    }

    private void tickKilling() {
        Entity e = this.level().getEntity(this.getGrabbedPlayerId());
        if (!(e instanceof ServerPlayer p) || !p.isAlive()) {
            resetToIdle();
            return;
        }
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.setYRot(this.grabYaw);
        this.yBodyRot = this.grabYaw;
        this.setYHeadRot(this.grabYaw);
        lockPlayer(p, this.killTicks % 5 == 0);
        if (--this.killTicks <= 0) {
            // death.attack.mob: "<player> was slain by Bacteria"; the grab cannot be cancelled by a totem
            p.hurt(this.damageSources().mobAttack(this), Float.MAX_VALUE);
            if (p.isAlive()) {
                p.hurt(this.damageSources().genericKill(), Float.MAX_VALUE);
            }
            resetToIdle();
        }
    }

    // ------------------------------------------------------------------ search

    /**
     * Searching: walk to the last place the player was seen, stand there and look around, then sweep the area with
     * waypoints that lead on in the direction the player was running and avoid places already checked.
     */
    private void startSearch() {
        this.setState(STATE_SEARCHING);
        this.phaseTicks = SEARCH_TICKS;
        this.targetId = null;
        this.setRealSpeed(SPEED_SEARCH);
        setTurn(TURN_SEARCH);
        this.searchOrigin = this.lastSeen != null ? this.lastSeen : this.position();
        this.searchTarget = null;
        this.searchPause = 0;
        this.searchStage = 0;
        this.searchMemory.clear();
        this.pathGoal = null;
        resetStuck();
        this.getNavigation().stop();
        if (this.lastSeen != null) {
            if (horizSqr(this.lastSeen) < 4.0D) {
                // already at the spot: look around first
                this.searchStage = 1;
                this.searchPause = 50 + this.random.nextInt(40);
            } else if (this.getNavigation().moveTo(this.lastSeen.x, this.lastSeen.y, this.lastSeen.z, NAV_MODIFIER)) {
                this.searchTarget = this.lastSeen;
            }
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
        setTurn(TURN_SEARCH);

        if (this.searchPause > 0) {
            this.searchPause--;
            this.getNavigation().stop();
            sweepLook();
            updateStuck(false);
            return;
        }
        if (tickSidestep()) {
            return;
        }
        if (this.searchTarget == null) {
            pickSearchTarget();
            return;
        }
        if (this.getNavigation().isDone() || horizSqr(this.searchTarget) < 2.25D) {
            remember(this.searchMemory, this.searchTarget);
            this.searchTarget = null;
            this.searchStage++;
            this.searchPause = this.searchStage <= 1 ? 50 + this.random.nextInt(40) : 12 + this.random.nextInt(30);
            this.getNavigation().stop();
            return;
        }
        if (updateStuck(true)) {
            this.searchTarget = null;
            this.searchPause = 10;
            this.getNavigation().stop();
        }
    }

    /** Slow left-right sweep of the body while the mob stands and listens. */
    private void sweepLook() {
        float dir = ((this.searchPause / 24) & 1) == 0 ? 1.0F : -1.0F;
        turnBodyTo(this.getYRot() + dir * 5.0F, 5.0F);
    }

    private void pickSearchTarget() {
        double base;
        double half;
        if (this.searchOrigin != null && horizSqr(this.searchOrigin) > SEARCH_RADIUS * SEARCH_RADIUS * 0.64D) {
            // drifted too far from where the search started: head back
            base = Math.atan2(this.searchOrigin.z - this.getZ(), this.searchOrigin.x - this.getX());
            half = 1.0D;
        } else if (this.searchStage <= 1 && this.lastSeenVel.lengthSqr() > 4.0E-4D) {
            // first sweep: the way the player was running
            base = Math.atan2(this.lastSeenVel.z, this.lastSeenVel.x);
            half = 0.9D;
        } else {
            Vec3 heading = Vec3.directionFromRotation(0.0F, this.getYRot());
            base = Math.atan2(heading.z, heading.x);
            half = 1.6D;
        }
        Vec3 spot = pickWaypoint(base, half, 6.0D, 18.0D, this.searchMemory, this.searchOrigin, SEARCH_RADIUS);
        if (spot == null) {
            spot = pickWaypoint(base, Math.PI, 4.0D, 14.0D, this.searchMemory, this.searchOrigin, SEARCH_RADIUS);
        }
        if (spot != null && this.getNavigation().moveTo(spot.x, spot.y, spot.z, NAV_MODIFIER)) {
            this.searchTarget = spot;
            return;
        }
        this.searchPause = 20 + this.random.nextInt(20);
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
        arms.setSoundKeyframeHandler(event -> onSoundKeyframe(event.getKeyframeData().getSound()));
        controllers.add(arms);
    }

    /** Animation time of the kill animation in seconds (it starts after the 4 tick blend). Client side. */
    public double getKillAnimSeconds(float partialTick) {
        return Math.max(0.0D, (this.clientKillTicks + partialTick - 4.0D) / 20.0D);
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
        // 0.5: one baked animation per situation (see BEDROCK_LAYERS); the arms controller no longer plays anything
        if (this.attackAnimTicks > 0 && phase == STATE_CHASING) {
            // the swing runs in real time, the legs of baked_sprint_attack are already baked at the sprint tempo
            boolean even = (this.lastAttackSerial & 1) == 0;
            if (mode == 2) {
                return state.setAndContinue(even ? ANIM_BAKED_ATTACK_RUN_A : ANIM_BAKED_ATTACK_RUN_B);
            }
            return state.setAndContinue(even ? ANIM_BAKED_ATTACK_STILL_A : ANIM_BAKED_ATTACK_STILL_B);
        }
        if (mode == 2) {
            return state.setAndContinue(BEDROCK_LAYERS ? ANIM_BAKED_SPRINT_TW : ANIM_BAKED_SPRINT);
        }
        if (mode == 1) {
            controller.setAnimationSpeed(animRate(1) / WALK_BAKED_RATE);
            if (!BEDROCK_LAYERS) {
                return state.setAndContinue(ANIM_BAKED_WALK);
            }
            return state.setAndContinue(phase == STATE_SEARCHING ? ANIM_BAKED_WALK_TW_SEARCH : ANIM_BAKED_WALK_TW);
        }
        return state.setAndContinue(ANIM_IDLE);
    }

    private PlayState armsController(AnimationState<BacteriaEntity> state) {
        // 0.5: the arms are part of the baked animations played by the movement controller (a second controller on the
        // same bones would replace them), so this controller stays idle
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
