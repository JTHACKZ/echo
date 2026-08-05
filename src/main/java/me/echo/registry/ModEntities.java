package me.echo.registry;

import me.echo.Echo;
import me.echo.entity.ConstructEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public class ModEntities {

    public static final EntityType<ConstructEntity> CONSTRUCT_ENTITY = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            new ResourceLocation("echo", "construct_entity"),
            FabricEntityTypeBuilder.create(MobCategory.MISC, ConstructEntity::new)
                    .dimensions(EntityDimensions.scalable(1.0f, 1.0f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static void register() {
        Echo.LOGGER.info("[Echo] Registered native ConstructEntity during startup!");
    }
}