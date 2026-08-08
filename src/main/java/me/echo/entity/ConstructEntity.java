package me.echo.entity;

import me.echo.engine.ConstructBehavior;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class ConstructEntity extends Entity {

    // Visual Scale
    private static final EntityDataAccessor<Float> SCALE_X = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SCALE_Y = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SCALE_Z = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);

    // Independent Collision Hitbox Scale
    private static final EntityDataAccessor<Float> HITBOX_X = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HITBOX_Y = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HITBOX_Z = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Integer> TRACKING_MODE = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> Y_OFFSET = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> OFFSET_X = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> OFFSET_Z = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Boolean> CROUCH_DROP = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> MAX_LIFESPAN = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.INT);

    private double lockedY = Double.NaN;
    private int ageTicks = 0;

    // RUNTIME IN-MEMORY COMPILED BEHAVIOR & OWNER REFERENCE
    private ConstructBehavior behavior = null;
    private ServerPlayer owner = null;

    public ConstructEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(SCALE_X, 1.0f);
        this.entityData.define(SCALE_Y, 1.0f);
        this.entityData.define(SCALE_Z, 1.0f);

        this.entityData.define(HITBOX_X, 1.0f);
        this.entityData.define(HITBOX_Y, 1.0f);
        this.entityData.define(HITBOX_Z, 1.0f);

        this.entityData.define(TRACKING_MODE, 0);
        this.entityData.define(Y_OFFSET, 0.0f);

        this.entityData.define(OFFSET_X, 0.0f);
        this.entityData.define(OFFSET_Z, 0.0f);
        this.entityData.define(CROUCH_DROP, true);
        this.entityData.define(MAX_LIFESPAN, 0);
    }

    public void setConstructScale(float x, float y, float z) {
        this.entityData.set(SCALE_X, x);
        this.entityData.set(SCALE_Y, y);
        this.entityData.set(SCALE_Z, z);
        this.setHitboxScale(x, y, z);
    }

    public void setHitboxScale(float hx, float hy, float hz) {
        this.entityData.set(HITBOX_X, hx);
        this.entityData.set(HITBOX_Y, hy);
        this.entityData.set(HITBOX_Z, hz);
        this.refreshDimensions();
    }

    public float getScaleX() { return this.entityData.get(SCALE_X); }
    public float getScaleY() { return this.entityData.get(SCALE_Y); }
    public float getScaleZ() { return this.entityData.get(SCALE_Z); }

    public float getHitboxX() { return this.entityData.get(HITBOX_X); }
    public float getHitboxY() { return this.entityData.get(HITBOX_Y); }
    public float getHitboxZ() { return this.entityData.get(HITBOX_Z); }

    public void setTrackingMode(int mode) {
        this.entityData.set(TRACKING_MODE, mode);
    }

    public void setYOffset(float offset) {
        this.entityData.set(Y_OFFSET, offset);
    }

    public float getYOffset() {
        return this.entityData.get(Y_OFFSET);
    }

    public void setRelativeOffset(float offX, float offZ) {
        this.entityData.set(OFFSET_X, offX);
        this.entityData.set(OFFSET_Z, offZ);
    }

    public float getOffsetX() { return this.entityData.get(OFFSET_X); }
    public float getOffsetZ() { return this.entityData.get(OFFSET_Z); }

    public void setCrouchDrop(boolean drop) {
        this.entityData.set(CROUCH_DROP, drop);
    }

    public boolean canCrouchDrop() {
        return this.entityData.get(CROUCH_DROP);
    }

    public void setMaxLifespan(int ticks) {
        this.entityData.set(MAX_LIFESPAN, ticks);
    }

    public int getMaxLifespan() {
        return this.entityData.get(MAX_LIFESPAN);
    }

    // THE MISSING METHODS FOR JAVA BYTECODE INJECTION!
    public void setBehavior(ConstructBehavior behavior) {
        this.behavior = behavior;
    }

    public void setOwner(ServerPlayer owner) {
        this.owner = owner;
    }

    @Override
    public boolean canBeCollidedWith() {
        if (canCrouchDrop()) {
            net.minecraft.world.entity.player.Player player = this.level().getNearestPlayer(this, 3.0);
            if (player != null && (player.isShiftKeyDown() || player.isCrouching())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
        // EMPTY OVERRIDE: Eliminates sticky velcro friction against walls
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(getHitboxX(), getHitboxY());
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            // RUN THE AI'S COMPILED NATIVE JAVA BYTECODE EVERY TICK!
            if (this.behavior != null && this.level() instanceof ServerLevel serverLevel && this.owner != null) {
                try {
                    this.behavior.tick(this, serverLevel, this.owner);
                } catch (Throwable ignored) {}
            }

            int maxTicks = getMaxLifespan();
            if (maxTicks > 0) {
                this.ageTicks++;
                if (this.ageTicks >= maxTicks) {
                    this.discard();
                    return;
                }
            }

            int mode = this.entityData.get(TRACKING_MODE);
            float yOff = getYOffset();

            if (mode > 0) {
                net.minecraft.world.entity.player.Player player = this.level().getNearestPlayer(this, 32.0);
                if (player != null) {
                    Vec3 fwd = Vec3.directionFromRotation(0, player.getYRot()).normalize();

                    if (mode == 1) {
                        Vec3 look = player.getLookAngle().normalize();
                        Vec3 target = player.getEyePosition().add(look.scale(3.5));
                        this.setPos(target.x, target.y - (getHitboxY() / 2.0f) + yOff, target.z);
                        this.setYRot(player.getYRot());
                        this.setXRot(player.getXRot());
                        this.setYHeadRot(player.getYRot());
                        this.setYBodyRot(player.getYRot());
                    } else if (mode == 2) {
                        double targetFloorY = player.getY() - (getHitboxY() / 2.0f) - 0.05;
                        if (Double.isNaN(this.lockedY) || Math.abs(targetFloorY - this.lockedY) > 1.0) {
                            this.lockedY = targetFloorY;
                        }
                        this.setPos(player.getX() + (fwd.x * getOffsetZ()), this.lockedY + yOff, player.getZ() + (fwd.z * getOffsetZ()));
                        this.setYRot(player.getYRot());
                        this.setXRot(0);
                    } else if (mode == 3) {
                        this.setPos(player.getX() + getOffsetX(), player.getY() + yOff, player.getZ() + getOffsetZ());
                        this.setXRot(0);
                    } else if (mode == 4) {
                        double targetFloorY = player.getY() - (getHitboxY() / 2.0f) - 0.05;
                        if (Double.isNaN(this.lockedY) || Math.abs(targetFloorY - this.lockedY) > 1.0) {
                            this.lockedY = targetFloorY;
                        }
                        this.setPos(player.getX() + (fwd.x * getOffsetZ()), this.lockedY + yOff, player.getZ() + (fwd.z * getOffsetZ()));
                        this.setYRot(player.getYRot());
                        this.setXRot(player.getXRot());
                        this.setYHeadRot(player.getYRot());
                        this.setYBodyRot(player.getYRot());
                    }
                }
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setConstructScale(tag.getFloat("ScaleX"), tag.getFloat("ScaleY"), tag.getFloat("ScaleZ"));
        setHitboxScale(tag.getFloat("HitX"), tag.getFloat("HitY"), tag.getFloat("HitZ"));
        setTrackingMode(tag.getInt("TrackingMode"));
        setYOffset(tag.getFloat("YOffset"));
        setRelativeOffset(tag.getFloat("OffsetX"), tag.getFloat("OffsetZ"));
        setCrouchDrop(tag.getBoolean("CrouchDrop"));
        setMaxLifespan(tag.getInt("MaxLifespan"));
        this.ageTicks = tag.getInt("AgeTicks");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("ScaleX", getScaleX());
        tag.putFloat("ScaleY", getScaleY());
        tag.putFloat("ScaleZ", getScaleZ());
        tag.putFloat("HitX", getHitboxX());
        tag.putFloat("HitY", getHitboxY());
        tag.putFloat("HitZ", getHitboxZ());
        tag.putInt("TrackingMode", this.entityData.get(TRACKING_MODE));
        tag.putFloat("YOffset", getYOffset());
        tag.putFloat("OffsetX", getOffsetX());
        tag.putFloat("OffsetZ", getOffsetZ());
        tag.putBoolean("CrouchDrop", canCrouchDrop());
        tag.putInt("MaxLifespan", getMaxLifespan());
        tag.putInt("AgeTicks", this.ageTicks);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }
}