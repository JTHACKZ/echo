package me.echo.constructs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.echo.Echo;
import me.echo.entity.ConstructEntity;
import me.echo.registry.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class ConstructToolbox {

    private static final List<ConstructEntity> activeEntities = new ArrayList<>();

    public static void executeBlueprint(ServerPlayer player, String rawJson) {
        try {
            String sanitizedJson = rawJson.trim();
            while (sanitizedJson.endsWith("}}")) {
                sanitizedJson = sanitizedJson.substring(0, sanitizedJson.length() - 1);
            }

            JsonObject json = JsonParser.parseString(sanitizedJson).getAsJsonObject();

            String name = json.has("name") ? json.get("name").getAsString() : "Construct";
            String shape = json.has("shape") ? json.get("shape").getAsString().toLowerCase() : "sphere";
            float size = json.has("size") ? json.get("size").getAsFloat() : 4.0f;
            int slot = json.has("slot") ? json.get("slot").getAsInt() : Echo.activeSlot;

            String modeStr = json.has("mode") ? json.get("mode").getAsString().toLowerCase() : "";

            if (slot == 1) Echo.slot1Name = name;
            if (slot == 2) Echo.slot2Name = name;
            if (slot == 3) Echo.slot3Name = name;

            ServerLevel level = player.serverLevel();

            // Despawn previous construct
            clearActiveConstructs();

            // Summon ONE custom modded ConstructEntity
            ConstructEntity construct = new ConstructEntity(ModEntities.CONSTRUCT_ENTITY, level);
            Vec3 spawnPos = player.position();

            // Smart Mode Resolution (0 = Static, 1 = Camera, 2 = Feet)
            int trackingMode = 0;
            if (modeStr.contains("camera") || modeStr.contains("eye") || modeStr.contains("follow")) {
                trackingMode = 1;
            } else if (modeStr.contains("feet") || modeStr.contains("boot") || modeStr.contains("under")) {
                trackingMode = 2;
            }

            // Apply shapes and smart default modes
            if (shape.contains("platform")) {
                construct.setConstructScale(size, 0.25f, size);
                if (trackingMode == 0) trackingMode = 2; // Default platforms to feet tracking
                spawnPos = player.position().add(0, -0.15, 0);
            } else if (shape.contains("wall") || shape.contains("barrier") || shape.contains("shield")) {
                construct.setConstructScale(size, size, 0.4f);
                if (trackingMode == 0) trackingMode = 1; // Default walls to camera tracking
                spawnPos = player.getEyePosition().add(player.getLookAngle().scale(3.5));
            } else if (shape.contains("beam") || shape.contains("laser") || shape.contains("ray")) {
                construct.setConstructScale(0.6f, 0.6f, 12.0f);
                if (trackingMode == 0) trackingMode = 1;
                spawnPos = player.getEyePosition().add(player.getLookAngle().scale(6.0));
            } else {
                construct.setConstructScale(size, size, size);
                spawnPos = player.position().add(0, 1.0, 0);
            }

            construct.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
            construct.setYRot(player.getYRot());
            construct.setXRot(player.getXRot());
            construct.setTrackingMode(trackingMode);

            level.addFreshEntity(construct);
            activeEntities.add(construct);

            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, spawnPos.x, spawnPos.y, spawnPos.z, 25, 0.5, 0.5, 0.5, 0.05);

            Echo.LOGGER.info("[ConstructToolbox] Summoned custom ConstructEntity '{}' (Shape: {}, Mode: {}) in Slot {}", name, shape, trackingMode, slot);

        } catch (Exception e) {
            Echo.LOGGER.error("[ConstructToolbox] Failed to assemble construct: {}", rawJson, e);
        }
    }

    public static void clearActiveConstructs() {
        for (ConstructEntity entity : activeEntities) {
            if (entity != null && entity.isAlive()) {
                entity.discard();
            }
        }
        activeEntities.clear();
    }
}