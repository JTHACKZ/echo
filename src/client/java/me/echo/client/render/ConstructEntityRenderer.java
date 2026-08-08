package me.echo.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.echo.entity.ConstructEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public class ConstructEntityRenderer extends EntityRenderer<ConstructEntity> {

    private static final ResourceLocation GLOW_TEXTURE = new ResourceLocation("minecraft", "textures/block/lime_stained_glass.png");

    public ConstructEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ConstructEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        float sx = entity.getScaleX();
        float sy = entity.getScaleY();
        float sz = entity.getScaleZ();

        // CENTER-PIVOT ROTATION: Shift origin to the vertical center of the box BEFORE rotating!
        poseStack.translate(0.0, sy / 2.0f, 0.0);
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));

        // Center the scaled mesh around our new pivot coordinate
        poseStack.translate(-sx / 2.0f, -sy / 2.0f, -sz / 2.0f);
        poseStack.scale(sx, sy, sz);

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucentEmissive(GLOW_TEXTURE));
        Matrix4f matrix = poseStack.last().pose();

        // Bright Glowing Green Lantern RGBA
        int r = 30, g = 255, b = 80, a = 180;
        int fullBright = 0xF000F0;

        drawBox(consumer, matrix, r, g, b, a, fullBright);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private void drawBox(VertexConsumer consumer, Matrix4f matrix, int r, int g, int b, int a, int light) {
        addVertex(consumer, matrix, 0, 0, 1, r, g, b, a, light, 0, 1, 0, 0, 1);
        addVertex(consumer, matrix, 1, 0, 1, r, g, b, a, light, 1, 1, 0, 0, 1);
        addVertex(consumer, matrix, 1, 1, 1, r, g, b, a, light, 1, 0, 0, 0, 1);
        addVertex(consumer, matrix, 0, 1, 1, r, g, b, a, light, 0, 0, 0, 0, 1);

        addVertex(consumer, matrix, 1, 0, 0, r, g, b, a, light, 0, 1, 0, 0, -1);
        addVertex(consumer, matrix, 0, 0, 0, r, g, b, a, light, 1, 1, 0, 0, -1);
        addVertex(consumer, matrix, 0, 1, 0, r, g, b, a, light, 1, 0, 0, 0, -1);
        addVertex(consumer, matrix, 1, 1, 0, r, g, b, a, light, 0, 0, 0, 0, -1);

        addVertex(consumer, matrix, 0, 1, 1, r, g, b, a, light, 0, 1, 0, 1, 0);
        addVertex(consumer, matrix, 1, 1, 1, r, g, b, a, light, 1, 1, 0, 1, 0);
        addVertex(consumer, matrix, 1, 1, 0, r, g, b, a, light, 1, 0, 0, 1, 0);
        addVertex(consumer, matrix, 0, 1, 0, r, g, b, a, light, 0, 0, 0, 1, 0);

        addVertex(consumer, matrix, 0, 0, 0, r, g, b, a, light, 0, 1, 0, -1, 0);
        addVertex(consumer, matrix, 1, 0, 0, r, g, b, a, light, 1, 1, 0, -1, 0);
        addVertex(consumer, matrix, 1, 0, 1, r, g, b, a, light, 1, 0, 0, -1, 0);
        addVertex(consumer, matrix, 0, 0, 1, r, g, b, a, light, 0, 0, 0, -1, 0);

        addVertex(consumer, matrix, 0, 0, 0, r, g, b, a, light, 0, 1, -1, 0, 0);
        addVertex(consumer, matrix, 0, 0, 1, r, g, b, a, light, 1, 1, -1, 0, 0);
        addVertex(consumer, matrix, 0, 1, 1, r, g, b, a, light, 1, 0, -1, 0, 0);
        addVertex(consumer, matrix, 0, 1, 0, r, g, b, a, light, 0, 0, -1, 0, 0);

        addVertex(consumer, matrix, 1, 0, 1, r, g, b, a, light, 0, 1, 1, 0, 0);
        addVertex(consumer, matrix, 1, 0, 0, r, g, b, a, light, 1, 1, 1, 0, 0);
        addVertex(consumer, matrix, 1, 1, 0, r, g, b, a, light, 1, 0, 1, 0, 0);
        addVertex(consumer, matrix, 1, 1, 1, r, g, b, a, light, 0, 0, 1, 0, 0);
    }

    private void addVertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, int r, int g, int b, int a, int light, float u, float v, float nx, float ny, float nz) {
        consumer.vertex(matrix, x, y, z)
                .color(r, g, b, a)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(nx, ny, nz)
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(ConstructEntity entity) {
        return GLOW_TEXTURE;
    }
}