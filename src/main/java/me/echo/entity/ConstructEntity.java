package me.echo.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class ConstructEntity extends Entity {

    private static final EntityDataAccessor<Float> SCALE_X = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SCALE_Y = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SCALE_Z = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> TRACKING_MODE = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.INT);

    public ConstructEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(SCALE_X, 1.0f);
        this.entityData.define(SCALE_Y, 1.0f);
        this.entityData.define(SCALE_Z, 1.0f);
        this.entityData.define(TRACKING_MODE, 0);
    }

    public void setConstructScale(float x, float y, float z) {
        this.entityData.set(SCALE_X, x);
        this.entityData.set(SCALE_Y, y);
        this.entityData.set(SCALE_Z, z);
        this.refreshDimensions();
    }

    public float getScaleX() { return this.entityData.get(SCALE_X); }
    public float getScaleY() { return this.entityData.get(SCALE_Y); }
    public float getScaleZ() { return this.entityData.get(SCALE_Z); }

    public void setTrackingMode(int mode) {
        this.entityData.set(TRACKING_MODE, mode);
    }

    @Override
    public boolean canBeCollidedWith() {
        return true; // SOLID HARD-LIGHT HITBOX
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(getScaleX(), getScaleY());
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            int mode = this.entityData.get(TRACKING_MODE);
            if (mode > 0) {
                net.minecraft.world.entity.player.Player player = this.level().getNearestPlayer(this, 32.0);
                if (player != null) {
                    if (mode == 1) {
                        // MODE 1: Camera Lock (Shields/Walls 3.5 blocks in front of eyes)
                        Vec3 look = player.getLookAngle().normalize();
                        Vec3 target = player.getEyePosition().add(look.scale(3.5));
                        this.setPos(target.x, target.y - (getScaleY() / 2.0f), target.z);
                        this.setYRot(player.getYRot());
                        this.setXRot(player.getXRot());
                    } else if (mode == 2) {
                        // MODE 2: Feet Lock (Platform directly under boots)
                        this.setPos(player.getX(), player.getY() - (getScaleY() / 2.0f) - 0.05, player.getZ());
                        this.setYRot(player.getYRot());
                        this.setXRot(0);
                    }
                }
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setConstructScale(tag.getFloat("ScaleX"), tag.getFloat("ScaleY"), tag.getFloat("ScaleZ"));
        setTrackingMode(tag.getInt("TrackingMode"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("ScaleX", getScaleX());
        tag.putFloat("ScaleY", getScaleY());
        tag.putFloat("ScaleZ", getScaleZ());
        tag.putInt("TrackingMode", this.entityData.get(TRACKING_MODE));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }
}