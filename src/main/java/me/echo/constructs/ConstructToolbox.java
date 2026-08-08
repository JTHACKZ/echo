package me.echo.constructs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.echo.Echo;
import me.echo.engine.ConstructBehavior;
import me.echo.engine.LiveJavaEngine;
import me.echo.engine.MethodLibrary;
import me.echo.entity.ConstructEntity;
import me.echo.registry.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConstructToolbox {

    private static final List<ConstructEntity> activeEntities = new ArrayList<>();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    public static void executeBlueprint(ServerPlayer player, String rawJson) {
        try {
            // Echo.java now feeds perfectly balanced JSON objects, so no string replacement hacks are needed!
            JsonObject json = JsonParser.parseString(rawJson.trim()).getAsJsonObject();

            if (json.has("load_method")) {
                String loadName = json.get("load_method").getAsString();
                MethodLibrary.MethodEntry savedEntry = MethodLibrary.getMethod(loadName);
                if (savedEntry != null) {
                    ConstructBehavior compiled = MethodLibrary.getOrCompileBehavior(loadName);
                    summonConstructFromProfile(player, savedEntry.name, savedEntry.shape, savedEntry.size, savedEntry.mode, savedEntry.yOffset, savedEntry.forwardOffset, savedEntry.crouchDrop, 0, compiled);
                    Echo.LOGGER.info("[ConstructToolbox] Loaded and summoned saved permanent method '{}'", loadName);
                    return;
                } else {
                    Echo.LOGGER.warn("[ConstructToolbox] Requested saved method '{}' not found on disk.", loadName);
                }
            }

            String name = json.has("name") ? json.get("name").getAsString() : "Construct";
            String shape = json.has("shape") ? json.get("shape").getAsString().toLowerCase() : "sphere";
            float size = json.has("size") ? json.get("size").getAsFloat() : 4.0f;
            int slot = json.has("slot") ? json.get("slot").getAsInt() : Echo.activeSlot;

            String modeStr = json.has("mode") ? json.get("mode").getAsString().toLowerCase() : "";
            float yOffset = json.has("y_offset") ? json.get("y_offset").getAsFloat() : 0.0f;

            float forwardOffset = 0.0f;
            if (json.has("forward_offset")) forwardOffset = json.get("forward_offset").getAsFloat();
            else if (json.has("z_offset")) forwardOffset = json.get("z_offset").getAsFloat();
            else if (json.has("x_offset")) forwardOffset = json.get("x_offset").getAsFloat();

            boolean crouchDrop = true;
            if (json.has("crouch_drop")) crouchDrop = json.get("crouch_drop").getAsBoolean();
            else if (json.has("fall_through_on_crouch")) crouchDrop = json.get("fall_through_on_crouch").getAsBoolean();

            int durationSeconds = json.has("duration") ? json.get("duration").getAsInt() : 0;
            int maxLifespanTicks = (durationSeconds > 0 && durationSeconds < 3600) ? durationSeconds * 20 : 0;

            String javaCodeSnippet = json.has("java_code") ? json.get("java_code").getAsString() : "";
            ConstructBehavior compiledBehavior = LiveJavaEngine.compile(javaCodeSnippet);

            if (json.has("save_as") && !json.get("save_as").getAsString().trim().isEmpty()) {
                String saveName = json.get("save_as").getAsString().trim();
                MethodLibrary.saveMethod(saveName, shape, size, modeStr, yOffset, forwardOffset, crouchDrop, javaCodeSnippet, compiledBehavior);
            }

            if (shape.contains("clear") || shape.contains("stop") || shape.contains("remove")) {
                clearActiveConstructs();
                Echo.LOGGER.info("[ConstructToolbox] Cleared all active constructs.");
                return;
            }

            if (slot == 1) Echo.slot1Name = name;
            if (slot == 2) Echo.slot2Name = name;
            if (slot == 3) Echo.slot3Name = name;

            summonConstructFromProfile(player, name, shape, size, modeStr, yOffset, forwardOffset, crouchDrop, maxLifespanTicks, compiledBehavior);

        } catch (Exception e) {
            Echo.LOGGER.error("[ConstructToolbox] Failed to assemble construct: {}", rawJson, e);
        }
    }

    private static void summonConstructFromProfile(ServerPlayer player, String name, String shape, float size, String modeStr, float yOffset, float forwardOffset, boolean crouchDrop, int maxLifespanTicks, ConstructBehavior compiledBehavior) {
        ServerLevel level = player.serverLevel();
        clearActiveConstructs();

        int trackingMode = -1;
        if (modeStr.contains("static") || modeStr.contains("stationary") || modeStr.contains("stay")) {
            trackingMode = 0;
        } else if (modeStr.contains("hover") || modeStr.contains("board") || modeStr.contains("tilt") || (modeStr.contains("camera") && modeStr.contains("feet"))) {
            trackingMode = 4;
        } else if (modeStr.contains("camera") || modeStr.contains("eye") || modeStr.contains("look")) {
            trackingMode = 1;
        } else if (modeStr.contains("feet") || modeStr.contains("boot") || modeStr.contains("under") || modeStr.contains("below")) {
            trackingMode = 2;
        } else if (modeStr.contains("body") || modeStr.contains("center") || modeStr.contains("around") || modeStr.contains("surround") || modeStr.contains("move") || modeStr.contains("follow")) {
            trackingMode = 3;
        }

        if (shape.contains("sphere") || shape.contains("dome") || shape.contains("cage") || shape.contains("box")) {
            if (trackingMode == -1 || trackingMode == 1) trackingMode = 3;
            spawnHollowRoom(level, player, size, yOffset, trackingMode, maxLifespanTicks, compiledBehavior);
            return;
        }

        ConstructEntity construct = new ConstructEntity(ModEntities.CONSTRUCT_ENTITY, level);
        Vec3 spawnPos = player.position();

        if (shape.contains("platform") || shape.contains("board")) {
            construct.setConstructScale(size, 0.25f, size);
            construct.setHitboxScale(size, 0.25f, size);
            if (trackingMode == -1) trackingMode = 2;
            spawnPos = player.position().add(0, -0.15 + yOffset, 0);
        } else if (shape.contains("wall") || shape.contains("barrier") || shape.contains("shield")) {
            construct.setConstructScale(size, size, 0.4f);
            construct.setHitboxScale(size, size, 0.4f);
            if (trackingMode == -1) trackingMode = 1;
            spawnPos = player.getEyePosition().add(player.getLookAngle().scale(3.5)).add(0, yOffset, 0);
            crouchDrop = false;
        } else if (shape.contains("beam") || shape.contains("laser") || shape.contains("ray")) {
            construct.setConstructScale(0.6f, 0.6f, 12.0f);
            construct.setHitboxScale(0.6f, 0.6f, 12.0f);
            if (trackingMode == -1) trackingMode = 1;
            spawnPos = player.getEyePosition().add(player.getLookAngle().scale(6.0)).add(0, yOffset, 0);
            crouchDrop = false;
        } else {
            construct.setConstructScale(size, size, size);
            construct.setHitboxScale(size, size, size);
            if (trackingMode == -1) trackingMode = 3;
            spawnPos = player.position().add(0, 1.0 + yOffset, 0);
        }

        construct.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
        construct.setYRot(player.getYRot());
        construct.setXRot(player.getXRot());
        construct.setTrackingMode(trackingMode);
        construct.setYOffset(yOffset);
        construct.setRelativeOffset(0.0f, forwardOffset);
        construct.setCrouchDrop(crouchDrop);
        construct.setMaxLifespan(maxLifespanTicks);
        construct.setOwner(player);
        construct.setBehavior(compiledBehavior);

        level.addFreshEntity(construct);
        activeEntities.add(construct);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, spawnPos.x, spawnPos.y, spawnPos.z, 25, 0.5, 0.5, 0.5, 0.05);
    }

    public static void executeBlueprintSequence(ServerPlayer player, List<String> rawJsons) {
        if (rawJsons == null || rawJsons.isEmpty()) return;

        executeBlueprint(player, rawJsons.get(0));

        if (rawJsons.size() > 1) {
            try {
                JsonObject firstJson = JsonParser.parseString(rawJsons.get(0).trim()).getAsJsonObject();
                int delaySeconds = firstJson.has("duration") ? firstJson.get("duration").getAsInt() : 2;
                if (delaySeconds <= 0 || delaySeconds >= 3600) delaySeconds = 2;

                int finalDelay = delaySeconds;
                SCHEDULER.schedule(() -> {
                    player.server.execute(() -> executeBlueprint(player, rawJsons.get(1)));
                }, finalDelay, TimeUnit.SECONDS);

            } catch (Exception ignored) {
                SCHEDULER.schedule(() -> {
                    player.server.execute(() -> executeBlueprint(player, rawJsons.get(1)));
                }, 2, TimeUnit.SECONDS);
            }
        }
    }

    private static void spawnHollowRoom(ServerLevel level, ServerPlayer player, float radius, float yOffset, int mode, int maxLifespanTicks, ConstructBehavior behavior) {
        Vec3 center = player.position().add(0, yOffset, 0);
        float half = radius / 2.0f;
        float wallThickness = 0.4f;

        spawnPerimeterWall(level, player, center.add(0, 0, -half), radius, radius, wallThickness, 0, mode, yOffset, 0, -half, maxLifespanTicks, behavior);
        spawnPerimeterWall(level, player, center.add(0, 0, half), radius, radius, wallThickness, 0, mode, yOffset, 0, half, maxLifespanTicks, null);
        spawnPerimeterWall(level, player, center.add(-half, 0, 0), wallThickness, radius, radius, 90, mode, yOffset, -half, 0, maxLifespanTicks, null);
        spawnPerimeterWall(level, player, center.add(half, 0, 0), wallThickness, radius, radius, 90, mode, yOffset, half, 0, maxLifespanTicks, null);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y + 1.0, center.z, 40, half, half, half, 0.05);
    }

    private static void spawnPerimeterWall(ServerLevel level, ServerPlayer owner, Vec3 pos, float sx, float sy, float sz, float yRot, int mode, float yOffset, float offX, float offZ, int maxLifespanTicks, ConstructBehavior behavior) {
        ConstructEntity wall = new ConstructEntity(ModEntities.CONSTRUCT_ENTITY, level);
        wall.setConstructScale(sx, sy, sz);
        wall.setHitboxScale(sx, sy, sz);
        wall.setPos(pos.x, pos.y, pos.z);
        wall.setYRot(yRot);
        wall.setXRot(0);
        wall.setTrackingMode(mode);
        wall.setYOffset(yOffset);
        wall.setRelativeOffset(offX, offZ);
        wall.setCrouchDrop(false);
        wall.setMaxLifespan(maxLifespanTicks);
        wall.setOwner(owner);
        wall.setBehavior(behavior);

        level.addFreshEntity(wall);
        activeEntities.add(wall);
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