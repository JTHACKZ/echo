package me.echo.client;

import me.echo.Echo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import me.echo.client.render.ConstructEntityRenderer;
import me.echo.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

@Environment(EnvType.CLIENT)
public class ECHOClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register((guiGraphics, tickDelta) -> {
            Minecraft client = Minecraft.getInstance();
            EntityRendererRegistry.register(ModEntities.CONSTRUCT_ENTITY, ConstructEntityRenderer::new);

            if (client.player != null) {
                // Pin to bottom right corner
                int width = client.getWindow().getGuiScaledWidth();
                int height = client.getWindow().getGuiScaledHeight();

                int x = width - 120;
                int y = height - 50;

                // Render the Constructs UI
                guiGraphics.drawString(client.font, "§2§l=== CONSTRUCTS ===", x, y - 15, 0xFFFFFF, true);
                guiGraphics.drawString(client.font, (Echo.activeSlot == 1 ? "§a▶ §f§l" : "§7  ") + Echo.slot1Name, x, y, 0xFFFFFF, true);
                guiGraphics.drawString(client.font, (Echo.activeSlot == 2 ? "§a▶ §f§l" : "§7  ") + Echo.slot2Name, x, y + 12, 0xFFFFFF, true);
                guiGraphics.drawString(client.font, (Echo.activeSlot == 3 ? "§a▶ §f§l" : "§7  ") + Echo.slot3Name, x, y + 24, 0xFFFFFF, true);
            }
        });
    }
}