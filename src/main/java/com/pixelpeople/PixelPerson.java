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

    /** Cubes that are no longer attached (kept on server, mirrored on clients through packets). */
    public final BitSet detached = new BitSet(PixelModel.COUNT);

    // ---- server state ----
    private int lastHitTick;
    private int getUpTicks;
    private boolean traitSet;
    private DamageSource pendingSource;

    // ---- client-only animation state ----
    public int stateTicks;
    public float lie, lieO, sit, sitO, drop, dropO, panic, panicO, bounce, bounceO, bounceV;
    public final float[] sx = new float[6], sz = new float[6], sxO = new float[6], szO = new float[6];
    private final float[] vx = new float[6], vz = new float[6];
    public final List<Debris> debris = new ArrayList<>();
    private static final Matrix4f[] SCRATCH = new Matrix4f[PixelModel.PARTS];
    static {
        for (int i = 0; i < SCRATCH.length; i++) SCRATCH[i] = new Matrix4f();
    }

    public PixelPerson(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
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
    public EntityDimensions getDimensions(Pose pose) {
        return getState() == STANDING ? super.getDimensions(pose) : EntityDimensions.scalable(1.6f, 0.5f);
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
        int count = Mth.clamp(Math.round(amount * 2.2f) + 1, 2, 40);
        int[] cubes = detachNear(pos, count);

        boolean heavy = amount >= 4f
                || getHealth() < getMaxHealth() * 0.35f
                || source.getDirectEntity() instanceof Projectile;
        int st = getState();
        if (st == STANDING) {
            if (heavy) {
                Vec3 fwd = Vec3.directionFromRotation(0f, yBodyRot);
                entityData.set(DATA_FACE_DOWN, dir.dot(fwd) > 0.3);
                lastHitTick = tickCount;
                setState(DOWN);
            }
        } else {
            lastHitTick = tickCount; // still being hit: stay limp
            if (st == GETTING_UP) setState(DOWN);
        }

        PacketDistributor.sendToPlayersTrackingEntity(this,
                new HitPayload(getId(), cubes, (float) dir.x, (float) dir.y, (float) dir.z, strength));
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
                    new HitPayload(getId(), all, (float) dir.x, (float) dir.y, (float) dir.z, 3f));
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

    public void clientHit(int[] cubes, Vec3 dir, float strength) {
        for (int idx : cubes) {
            if (!detached.get(idx)) {
                detached.set(idx);
                spawnDebris(idx, dir, strength);
            }
        }
        // jerk every limb: standing people flinch, lying people get tossed around like dolls
        for (int i = 0; i < 6; i++) {
            vx[i] += (random.nextFloat() - 0.5f) * 0.9f * strength;
            vz[i] += (random.nextFloat() - 0.5f) * 0.9f * strength;
        }
        if (getState() != STANDING) bounceV += 1.2f * strength;
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    private void clientTick() {
        stateTicks++;
        lieO = lie; sitO = sit; dropO = drop; panicO = panic; bounceO = bounce;

        int st = getState();
        if (st == DOWN) {
            lie = Math.min(1f, lie + 0.25f);
            sit = 0f;
            drop = lie;
        } else if (st == GETTING_UP) {
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
            lie = Math.max(0f, lie - 0.25f);
            sit = 0f;
            drop = lie;
        }

        float panicTarget = (st == STANDING && !isDeadOrDying() && getHealth() < getMaxHealth() * 0.35f
                && getTrait() != BRAVE) ? 1f : 0f;
        panic += (panicTarget - panic) * 0.25f;

        for (int i = 0; i < 6; i++) {
            sxO[i] = sx[i];
            szO[i] = sz[i];
            float rx = PixelPose.restX(i) * lie, rz = PixelPose.restZ(i) * lie;
            vx[i] += -0.22f * (sx[i] - rx) - 0.12f * vx[i];
            vz[i] += -0.22f * (sz[i] - rz) - 0.12f * vz[i];
            sx[i] += vx[i];
            sz[i] += vz[i];
        }

        bounceV -= 0.35f;
        bounce += bounceV;
        if (bounce < 0f) {
            bounce = 0f;
            bounceV = Math.abs(bounceV) > 0.5f ? -bounceV * 0.3f : 0f;
        }

        tickDebris();
    }

    private void spawnDebris(int idx, Vec3 dir, float strength) {
        if (debris.size() >= 500) return;
        PixelPose.compute(this, 1.0f, SCRATCH);
        Vector3f p = new Vector3f(PixelModel.CX[idx], PixelModel.CY[idx], PixelModel.CZ[idx]);
        SCRATCH[PixelModel.PART[idx]].transformPosition(p);
        double yaw = Math.toRadians(yBodyRot);
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double s = PixelModel.SCALE;

        Debris d = new Debris();
        d.cube = idx;
        d.x = d.xo = getX() + (p.x * cos - p.z * sin) * s;
        d.y = d.yo = getY() + p.y * s;
        d.z = d.zo = getZ() + (p.x * sin + p.z * cos) * s;
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
