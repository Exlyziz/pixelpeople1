package com.pixelpeople;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A Steve made of single-pixel cubes. Server side: health, AI, pain, knock-down / get-up state machine
 * and which cubes have fallen off. Client side: purely visual ragdoll springs and flying debris.
 */
public class PixelPerson extends PathfinderMob {
    public static final int STANDING = 0, DOWN = 1, GETTING_UP = 2;
    public static final int GETUP_TICKS = 32;
    public static final int BRAVE = 0, COWARD = 1, CALM = 2;

    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(PixelPerson.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_FACE_DOWN =
            SynchedEntityData.defineId(PixelPerson.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_TRAIT =
            SynchedEntityData.defineId(PixelPerson.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_SABLE =
            SynchedEntityData.defineId(PixelPerson.class, EntityDataSerializers.BOOLEAN);

    /** Cubes that are no longer attached (kept on server, mirrored on clients through packets). */
    public final BitSet detached = new BitSet(PixelModel.COUNT);

    // ---- server state ----
    private int lastHitTick;
    private int getUpTicks;
    private boolean traitSet;
    private boolean sableDriven;
    private DamageSource pendingSource;

    // ---- client-only animation state ----
    public int stateTicks;
    public float lie, lieO, sit, sitO, drop, dropO, panic, panicO, bounce, bounceO, bounceV;
    public float lieV, twist, twistO, twistV;
    private final float[] offX = new float[6], offZ = new float[6];
    /** How strongly each part lags behind when the whole body rotates (head lags most). */
    private static final float[] LAG = {0.5f, 0f, 0.3f, 0.3f, 0.15f, 0.15f};
    public final float[] sx = new float[6], sz = new float[6], sxO = new float[6], szO = new float[6];
    private final float[] vx = new float[6], vz = new float[6];
    public final List<Debris> debris = new ArrayList<>();
    // ---- physics ragdoll (client) ----
    /** Rigid-body ragdoll that runs while the person is knocked down. */
    public RagdollPhysics phys;
    private boolean physLive;
    private final java.util.HashMap<Long, Boolean> solidCache = new java.util.HashMap<>();
    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
    private Vec3 pendPos = Vec3.ZERO, pendDir = Vec3.ZERO;
    private float pendStrength;
    private int pendTick = -1000;
    private static final Matrix4f[] SCR = new Matrix4f[PixelModel.PARTS];
    private static final Matrix4f[] RM = new Matrix4f[PixelModel.PARTS];
    static {
        for (int i = 0; i < SCR.length; i++) { SCR[i] = new Matrix4f(); RM[i] = new Matrix4f(); }
    }

    public PixelPerson(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 80.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, STANDING);
        builder.define(DATA_FACE_DOWN, false);
        builder.define(DATA_TRAIT, CALM);
        builder.define(DATA_SABLE, false);
    }

    // ------------------------------------------------------------------ AI

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.6) {
            @Override public boolean canUse() { return getTrait() != BRAVE && super.canUse(); }
        });
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, true) {
            @Override public boolean canUse() { return getTrait() == BRAVE && super.canUse(); }
            @Override public boolean canContinueToUse() { return getTrait() == BRAVE && super.canContinueToUse(); }
        });
        goalSelector.addGoal(3, new AvoidEntityGoal<Player>(this, Player.class, 6.0f, 1.0, 1.4) {
            @Override public boolean canUse() { return getTrait() == COWARD && super.canUse(); }
        });
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override public boolean canUse() { return getTrait() == BRAVE && super.canUse(); }
        });
    }

    public int getState() { return entityData.get(DATA_STATE); }
    public boolean isFaceDown() { return entityData.get(DATA_FACE_DOWN); }
    public int getTrait() { return entityData.get(DATA_TRAIT); }
    /** True while the Sable: Player Ragdoll mod owns the ragdoll (it draws the body, not us). */
    public boolean isSableDriven() { return entityData.get(DATA_SABLE); }

    private void setState(int s) {
        if (getState() != s) {
            entityData.set(DATA_STATE, s);
            refreshDimensions();
        }
    }

    @Override
    protected boolean isImmobile() {
        return super.isImmobile() || getState() != STANDING;
    }

    @Override
    public EntityDimensions getDefaultDimensions(Pose pose) {
        return getState() == STANDING ? super.getDefaultDimensions(pose) : EntityDimensions.scalable(1.6f, 0.5f);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(3.0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_STATE.equals(key) && level().isClientSide) {
            stateTicks = 0;
            lieV = 0f;
            if (getState() == DOWN) { // every fall ends in a different dropped-doll pose
                for (int i = 0; i < 6; i++) {
                    offX[i] = i == PixelModel.BODY ? 0f : (random.nextFloat() - 0.5f) * 0.9f;
                    offZ[i] = i == PixelModel.BODY ? 0f : (random.nextFloat() - 0.5f) * 0.7f;
                }
            }
            refreshDimensions();
        }
    }

    // ------------------------------------------------------------------ sounds

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    // ------------------------------------------------------------------ server logic

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientTick();
        } else if (!isDeadOrDying()) {
            serverTick();
        }
    }

    private void serverTick() {
        if (!traitSet) {
            entityData.set(DATA_TRAIT, random.nextInt(3));
            traitSet = true;
        }
        int st = getState();
        if (Config.USE_SABLE.get() && SableBridge.available()) {
            if (sableDriven) {
                if (tickCount - lastHitTick > 10 && !SableBridge.isRagdolled(this)) {
                    sableDriven = false;                       // Sable finished: stand up with our own animation
                    entityData.set(DATA_SABLE, false);
                    getUpTicks = 0;
                    setState(GETTING_UP);
                } else if (tickCount - lastHitTick >= Math.max(recoverDelay(), 40)) {
                    SableBridge.release(this);                 // not hit for a while: let it get up
                }
                return;
            }
            if (st != GETTING_UP && SableBridge.isRagdolled(this)) {
                // some other mod (Ragdoll Reactions) ragdolled us: follow it
                sableDriven = true;
                entityData.set(DATA_SABLE, true);
                entityData.set(DATA_FACE_DOWN, false);
                lastHitTick = tickCount;
                setState(DOWN);
                return;
            }
        }
        if (st == DOWN && tickCount - lastHitTick >= recoverDelay()) {
            getUpTicks = 0;
            setState(GETTING_UP);
        } else if (st == GETTING_UP && ++getUpTicks >= GETUP_TICKS) {
            setState(STANDING);
        }
    }

    private int recoverDelay() {
        return switch (getTrait()) {
            case COWARD -> 60;
            case BRAVE -> 30;
            default -> 40;
        };
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        pendingSource = source;
        boolean hurt = super.hurt(source, amount);
        pendingSource = null;
        if (hurt && !level().isClientSide && !isDeadOrDying()) {
            onPain(source, amount);
        }
        return hurt;
    }

    private void onPain(DamageSource source, float amount) {
        Vec3 dir = hitDirection(source);
        Vec3 pos = hitPosition(source);
        float strength = Mth.clamp(amount / 4f, 0.4f, 3f);
        int count = Mth.clamp(Math.round(amount * 3.0f) + 2, 3, 70);
        int[] cubes = detachNear(pos, count);

        boolean heavy = amount >= 4f
                || getHealth() < getMaxHealth() * 0.35f
                || source.getDirectEntity() instanceof Projectile;
        int st = getState();
        if (st == STANDING) {
            if (heavy) knockDown(dir, strength);
        } else {
            lastHitTick = tickCount; // still being hit: stay limp
            if (st == GETTING_UP) knockDown(dir, strength);
        }

        PacketDistributor.sendToPlayersTrackingEntity(this,
                new HitPayload(getId(), cubes, (float) dir.x, (float) dir.y, (float) dir.z, strength,
                        (float) pos.x, (float) pos.y, (float) pos.z));
    }

    private void knockDown(Vec3 dir, float strength) {
        Vec3 fwd = Vec3.directionFromRotation(0f, yBodyRot);
        entityData.set(DATA_FACE_DOWN, dir.dot(fwd) > 0.3);
        lastHitTick = tickCount;
        boolean sable = false;
        if (Config.USE_SABLE.get() && SableBridge.available() && level() instanceof ServerLevel sl) {
            Vec3 spin = new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).scale(10.0);
            sable = SableBridge.isRagdolled(this)
                    || SableBridge.launch(sl, this, new Vec3(dir.x, 0.35, dir.z).normalize().scale(5.0 + 1.5 * strength), spin);
        }
        sableDriven = sable;
        entityData.set(DATA_SABLE, sable);
        setState(DOWN);
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            Vec3 dir = hitDirection(source != null ? source : damageSources().generic());
            int[] all = new int[PixelModel.COUNT - detached.cardinality()];
            int n = 0;
            for (int i = detached.nextClearBit(0); i < PixelModel.COUNT; i = detached.nextClearBit(i + 1)) {
                all[n++] = i;
                detached.set(i);
            }
            PacketDistributor.sendToPlayersTrackingEntity(this,
                    new HitPayload(getId(), all, (float) dir.x, (float) dir.y, (float) dir.z, 3f,
                    (float) getX(), (float) (getY() + 0.9), (float) getZ()));
        }
        super.die(source);
    }

    @Override
    protected void tickDeath() {
        ++deathTime;
        if (deathTime >= 50 && !level().isClientSide() && !isRemoved()) {
            remove(Entity.RemovalReason.KILLED);
        }
    }

    private Vec3 hitDirection(DamageSource src) {
        Entity direct = src.getDirectEntity();
        if (direct instanceof Projectile) {
            Vec3 m = direct.getDeltaMovement();
            if (m.lengthSqr() > 1.0E-4) return m.normalize();
        }
        Entity attacker = src.getEntity();
        if (attacker != null) {
            Vec3 d = position().subtract(attacker.position());
            d = new Vec3(d.x, 0, d.z);
            if (d.lengthSqr() > 1.0E-4) {
                d = d.normalize();
                return new Vec3(d.x, 0.25, d.z).normalize();
            }
        }
        return new Vec3(random.nextDouble() - 0.5, 0.5, random.nextDouble() - 0.5).normalize();
    }

    private Vec3 hitPosition(DamageSource src) {
        AABB box = getBoundingBox();
        Entity direct = src.getDirectEntity();
        if (direct instanceof Projectile) {
            Vec3 p = direct.position();
            return new Vec3(Mth.clamp(p.x, box.minX, box.maxX), Mth.clamp(p.y, box.minY, box.maxY),
                    Mth.clamp(p.z, box.minZ, box.maxZ));
        }
        Entity attacker = src.getEntity();
        if (attacker != null) {
            Vec3 eye = attacker.getEyePosition();
            Optional<Vec3> c = box.clip(eye, eye.add(attacker.getLookAngle().scale(8.0)));
            if (c.isPresent()) return c.get();
        }
        return new Vec3(getX() + (random.nextDouble() - 0.5) * 0.4,
                getY() + random.nextDouble() * box.getYsize(),
                getZ() + (random.nextDouble() - 0.5) * 0.4);
    }

    /** Picks the `count` attached cubes closest to the hit point (random ones when lying down). */
    private int[] detachNear(Vec3 hitWorld, int count) {
        int remaining = PixelModel.COUNT - detached.cardinality();
        if (remaining <= 0) return new int[0];
        count = Math.min(count, remaining);

        float hx, hy, hz;
        if (getState() == STANDING) {
            Vec3 rel = hitWorld.subtract(position());
            double yaw = Math.toRadians(yBodyRot);
            double cos = Math.cos(yaw), sin = Math.sin(yaw);
            hx = (float) ((rel.x * cos + rel.z * sin) / PixelModel.SCALE);   // character's left
            hy = (float) (rel.y / PixelModel.SCALE);
            hz = (float) ((-rel.x * sin + rel.z * cos) / PixelModel.SCALE);  // forward
        } else {
            int r;
            do { r = random.nextInt(PixelModel.COUNT); } while (detached.get(r));
            hx = PixelModel.CX[r]; hy = PixelModel.CY[r]; hz = PixelModel.CZ[r];
        }

        int[] best = new int[count];
        float[] bestScore = new float[count];
        int filled = 0;
        for (int i = detached.nextClearBit(0); i < PixelModel.COUNT; i = detached.nextClearBit(i + 1)) {
            float dx = PixelModel.CX[i] - hx, dy = PixelModel.CY[i] - hy, dz = PixelModel.CZ[i] - hz;
            float score = dx * dx + dy * dy + dz * dz + random.nextFloat() * 3f;
            if (filled < count) {
                int j = filled++;
                while (j > 0 && bestScore[j - 1] > score) { bestScore[j] = bestScore[j - 1]; best[j] = best[j - 1]; j--; }
                bestScore[j] = score; best[j] = i;
            } else if (score < bestScore[count - 1]) {
                int j = count - 1;
                while (j > 0 && bestScore[j - 1] > score) { bestScore[j] = bestScore[j - 1]; best[j] = best[j - 1]; j--; }
                bestScore[j] = score; best[j] = i;
            }
        }
        for (int c : best) detached.set(c);
        return best;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (!detached.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new MaskPayload(getId(), detached.toByteArray()));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByteArray("Detached", detached.toByteArray());
        tag.putInt("Trait", getTrait());
        tag.putBoolean("TraitSet", traitSet);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        detached.clear();
        detached.or(BitSet.valueOf(tag.getByteArray("Detached")));
        traitSet = tag.getBoolean("TraitSet");
        if (traitSet) entityData.set(DATA_TRAIT, tag.getInt("Trait"));
    }

    // ------------------------------------------------------------------ client: ragdoll + debris

    public void clientSetMask(byte[] bits) {
        detached.clear();
        detached.or(BitSet.valueOf(bits));
    }

    public void clientHit(int[] cubes, Vec3 dir, float strength, Vec3 pos) {
        float keep = cubes.length > 250 ? 250f / cubes.length : 1f; // death: only some pixels become debris
        computeRenderMatrices(1.0f, RM);
        for (int idx : cubes) {
            if (!detached.get(idx)) {
                detached.set(idx);
                if (keep >= 1f || random.nextFloat() < keep) spawnDebris(idx, dir, strength, RM);
            }
        }
        // the hit shoves the physics ragdoll; if it is not running yet (the knock-down is still
        // on its way from the server) remember the hit and apply it when the ragdoll starts
        if (getState() == DOWN && physLive && phys != null) {
            physicsHit(pos, dir, strength);
        } else {
            pendPos = pos; pendDir = dir; pendStrength = strength; pendTick = tickCount;
        }
        // standing people flinch: jerk every limb
        for (int i = 0; i < 6; i++) {
            vx[i] += (random.nextFloat() - 0.5f) * 0.9f * strength;
            vz[i] += (random.nextFloat() - 0.5f) * 0.9f * strength;
        }
    }

    private void physicsHit(Vec3 pos, Vec3 dir, float strength) {
        int body = phys.nearestBody(pos.x, pos.y, pos.z);
        double mag = 3.0 + 1.5 * strength;
        phys.hit(body, pos.x, pos.y, pos.z,
                dir.x * mag, Math.max(0.0, dir.y) * mag + 1.5 + strength, dir.z * mag, 0.6);
    }

    private void landed(float v) {
        for (int i = 0; i < 6; i++) {
            vx[i] += (random.nextFloat() - 0.5f) * v * 3f;
            vz[i] += (random.nextFloat() - 0.5f) * v * 3f;
        }
        bounceV += v * 3f;
        twistV += (random.nextFloat() - 0.5f) * v * 0.6f;
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    private void clientTick() {
        stateTicks++;
        lieO = lie; sitO = sit; dropO = drop; panicO = panic; bounceO = bounce; twistO = twist;

        int st = getState();
        if (st == DOWN) {
            // real rigid-body ragdoll; the scripted "lying" values are only the fallback / get-up start pose
            if (isSableDriven()) {
                phys = null;
                physLive = false;
            } else {
                if (!physLive || phys == null) initPhysics();
                if (phys != null) stepPhysics();
            }
            lieV = 0f;
            lie = 1f;
            sit = 0f;
            drop = 1f;
        } else if (st == GETTING_UP) {
            physLive = false;
            if (phys != null) phys.snapshotPrev(); // freeze: it is blended into the get-up animation
            lieV = 0f;
            float t = Math.min(1f, stateTicks / (float) GETUP_TICKS);
            if (isFaceDown()) {            // push up like a plank
                lie = 1f - smooth(t);
                sit = 0f;
                drop = lie;
            } else if (t < 0.5f) {         // sit up first ...
                lie = 1f - smooth(t * 2f);
                sit = 1f - lie;
                drop = 1f;
            } else {                       // ... then swing the legs down and stand
                lie = 0f;
                sit = 1f - smooth((t - 0.5f) * 2f);
                drop = sit;
            }
        } else {
            phys = null;
            physLive = false;
            lieV = 0f;
            lie = Math.max(0f, lie - 0.25f);
            sit = 0f;
            drop = lie;
        }

        float panicTarget = (st == STANDING && !isDeadOrDying() && getHealth() < getMaxHealth() * 0.35f
                && getTrait() != BRAVE) ? 1f : 0f;
        panic += (panicTarget - panic) * 0.25f;

        // limb springs: firm while standing, loose and floppy while lying
        float side = isFaceDown() ? 1f : -1f;
        float dR = side * Mth.HALF_PI * (lie - lieO);   // how much the whole body just rotated
        float k = 0.22f - 0.10f * lie;
        float c = 0.12f - 0.04f * lie;
        for (int i = 0; i < 6; i++) {
            sxO[i] = sx[i];
            szO[i] = sz[i];
            vx[i] += -dR * LAG[i];                      // inertia: limbs lag behind the rotating body
            float rx = (PixelPose.restX(i) + offX[i]) * lie;
            float rz = (PixelPose.restZ(i) + offZ[i]) * lie;
            vx[i] += -k * (sx[i] - rx) - c * vx[i];
            vz[i] += -k * (sz[i] - rz) - c * vz[i];
            sx[i] += vx[i];
            sz[i] += vz[i];
        }

        twistV += -0.05f * twist - 0.08f * twistV;
        twist += twistV;

        bounceV -= 0.35f;
        bounce += bounceV;
        if (bounce < 0f) {
            bounce = 0f;
            bounceV = Math.abs(bounceV) > 0.5f ? -bounceV * 0.3f : 0f;
        }

        tickDebris();
    }

    // ------------------------------------------------------------------ physics ragdoll glue

    private void initPhysics() {
        Matrix4f[] m = new Matrix4f[PixelModel.PARTS];
        for (int i = 0; i < m.length; i++) m[i] = new Matrix4f();
        scripted(1.0f, m);
        double[][] pos = new double[PixelModel.PARTS][3], rot = new double[PixelModel.PARTS][4];
        Vector3f c = new Vector3f();
        Quaternionf q = new Quaternionf();
        for (int i = 0; i < m.length; i++) {
            c.set(PixelModel.CEN[i][0], PixelModel.CEN[i][1], PixelModel.CEN[i][2]);
            m[i].transformPosition(c);
            pos[i][0] = getX() + c.x; pos[i][1] = getY() + c.y; pos[i][2] = getZ() + c.z;
            m[i].getNormalizedRotation(q);
            rot[i][0] = q.x; rot[i][1] = q.y; rot[i][2] = q.z; rot[i][3] = q.w;
        }
        RagdollPhysics rp = new RagdollPhysics();
        rp.init(pos, rot);
        Vec3 mv = getDeltaMovement();
        rp.setAllVelocity(mv.x * 20.0, mv.y * 20.0, mv.z * 20.0);
        phys = rp;
        physLive = true;
        if (tickCount - pendTick <= 10) physicsHit(pendPos, pendDir, pendStrength);
    }

    private void stepPhysics() {
        RagdollPhysics rp = phys;
        rp.snapshotPrev();
        solidCache.clear();
        rp.step(this::solidAt);
        RagdollPhysics.Body t = rp.b[PixelModel.BODY];
        // keep the ragdoll near the (server controlled) hitbox
        rp.shift((getX() - t.x[0]) * 0.05, 0.0, (getZ() - t.x[2]) * 0.05);
        if (!rp.isFinite() || t.x[1] < getY() - 4.0) {
            phys = null;
            physLive = false;
        }
    }

    private boolean solidAt(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Boolean cached = solidCache.get(key);
        if (cached != null) return cached;
        mpos.set(x, y, z);
        BlockState s = level().getBlockState(mpos);
        boolean r = !s.getCollisionShape(level(), mpos).isEmpty();
        solidCache.put(key, r);
        return r;
    }

    /** Pose of the body parts when no physics is involved (walking, flinching, getting up). */
    private void scripted(float pt, Matrix4f[] out) {
        PixelPose.compute(this, pt, SCR);
        float yaw = Mth.rotLerp(pt, yBodyRotO, yBodyRot) * Mth.DEG_TO_RAD;
        for (int i = 0; i < out.length; i++) {
            out[i].identity().rotateY(-yaw).scale(PixelModel.SCALE).mul(SCR[i]);
        }
    }

    /**
     * Matrices that map a part's character-space pixel coordinates to the entity's render space (blocks),
     * for the renderer and for the debris spawner: walking pose, physics ragdoll, or the blend of the two
     * while getting up.
     */
    public void computeRenderMatrices(float pt, Matrix4f[] out) {
        scripted(pt, out);
        int st = getState();
        if (phys == null || st == STANDING) return;
        float w = st == GETTING_UP ? smooth((stateTicks + pt) / 10f) : 0f; // 0 = pure physics
        if (w >= 1f) return;
        double ex = Mth.lerp(pt, xo, getX()), ey = Mth.lerp(pt, yo, getY()), ez = Mth.lerp(pt, zo, getZ());
        for (int i = 0; i < PixelModel.PARTS; i++) {
            RagdollPhysics.Body b = phys.b[i];
            float x = (float) (Mth.lerp(pt, b.ox[0], b.x[0]) - ex);
            float y = (float) (Mth.lerp(pt, b.ox[1], b.x[1]) - ey);
            float z = (float) (Mth.lerp(pt, b.ox[2], b.x[2]) - ez);
            Quaternionf q = new Quaternionf((float) b.oq[0], (float) b.oq[1], (float) b.oq[2], (float) b.oq[3])
                    .nlerp(new Quaternionf((float) b.q[0], (float) b.q[1], (float) b.q[2], (float) b.q[3]), pt);
            float[] c = PixelModel.CEN[i];
            Matrix4f pm = new Matrix4f().translate(x, y, z).rotate(q).scale(PixelModel.SCALE).translate(-c[0], -c[1], -c[2]);
            if (w <= 0f) {
                out[i].set(pm);
            } else {
                Vector3f oa = pm.transformPosition(new Vector3f());
                Vector3f ob = out[i].transformPosition(new Vector3f());
                Quaternionf qa = pm.getNormalizedRotation(new Quaternionf());
                Quaternionf qb = out[i].getNormalizedRotation(new Quaternionf());
                qa.slerp(qb, w);
                oa.lerp(ob, w);
                out[i].identity().translate(oa).rotate(qa).scale(PixelModel.SCALE);
            }
        }
    }

    private void spawnDebris(int idx, Vec3 dir, float strength, Matrix4f[] m) {
        if (debris.size() >= 500) return;
        Vector3f p = new Vector3f(PixelModel.CX[idx], PixelModel.CY[idx], PixelModel.CZ[idx]);
        m[PixelModel.PART[idx]].transformPosition(p);

        Debris d = new Debris();
        d.cube = idx;
        d.x = d.xo = getX() + p.x;
        d.y = d.yo = getY() + p.y;
        d.z = d.zo = getZ() + p.z;
        double k = 0.10 * strength;
        d.vx = dir.x * k + (random.nextDouble() - 0.5) * 0.12;
        d.vy = Math.max(0.0, dir.y) * k + 0.05 + random.nextDouble() * 0.10;
        d.vz = dir.z * k + (random.nextDouble() - 0.5) * 0.12;
        d.rvx = (random.nextFloat() - 0.5f) * 0.8f;
        d.rvy = (random.nextFloat() - 0.5f) * 0.8f;
        d.rvz = (random.nextFloat() - 0.5f) * 0.8f;
        d.life = 28 + random.nextInt(18); // 1.4 - 2.2 seconds, then the pixel disappears
        debris.add(d);
    }

    private void tickDebris() {
        double h = PixelModel.SCALE * 0.5;
        Iterator<Debris> it = debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            if (++d.age >= d.life) { it.remove(); continue; }
            d.xo = d.x; d.yo = d.y; d.zo = d.z;
            d.vy -= 0.04;
            d.vx *= 0.98; d.vz *= 0.98;
            double nx = d.x + d.vx, ny = d.y + d.vy, nz = d.z + d.vz;
            BlockPos bp = BlockPos.containing(nx, ny - h, nz);
            BlockState bs = level().getBlockState(bp);
            if (!bs.getCollisionShape(level(), bp).isEmpty()) {
                ny = bp.getY() + 1 + h;
                d.vy = -d.vy * 0.4;
                if (Math.abs(d.vy) < 0.03) d.vy = 0;
                d.vx *= 0.6; d.vz *= 0.6;
                d.rvx *= 0.6f; d.rvy *= 0.6f; d.rvz *= 0.6f;
            }
            d.x = nx; d.y = ny; d.z = nz;
            d.rx += d.rvx; d.ry += d.rvy; d.rz += d.rvz;
        }
    }

    /** One flying pixel (client only). */
    public static final class Debris {
        public int cube, age, life;
        public double x, y, z, xo, yo, zo, vx, vy, vz;
        public float rx, ry, rz, rvx, rvy, rvz;
    }
}
