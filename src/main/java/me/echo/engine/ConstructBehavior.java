package me.echo.engine;

import me.echo.entity.ConstructEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public interface ConstructBehavior {
    void tick(ConstructEntity construct, ServerLevel level, ServerPlayer owner);
}