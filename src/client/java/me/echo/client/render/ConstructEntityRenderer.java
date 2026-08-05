package me.echo.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.echo.entity.ConstructEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public class ConstructEntityRenderer extends EntityRenderer<ConstructEntity> {

    private static final ResourceLocation GLOW_TEXTURE = new ResourceLocation("minecraft", "textures/misc/white.png");

    public ConstructEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ConstructEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));

        float sx = entity.getScaleX();
        float sy = entity.getScaleY();
        float sz = entity.getScaleZ();
        poseStack.scale(sx, sy, sz);

        poseStack.translate(-0.5, 0.0, -0.5);

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucentEmissive(GLOW_TEXTURE));
        Matrix4f matrix = poseStack.last().pose();

        int r = 25, g = 255, b = 75, a = 160;
        int fullBright = 0xF000F0;

        drawBox(consumer, matrix, r, g, b, a, fullBright);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private void drawBox(VertexConsumer consumer, Matrix4f matrix, int r, int g, int b, int a, int light) {
        addVertex(consumer, matrix, 0, 0, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 0, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 1, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 1, 1, r, g, b, a, light);

        addVertex(consumer, matrix, 1, 0, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 0, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 1, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 1, 0, r, g, b, a, light);

        addVertex(consumer, matrix, 0, 1, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 1, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 1, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 1, 0, r, g, b, a, light);

        addVertex(consumer, matrix, 0, 0, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 0, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 0, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 0, 1, r, g, b, a, light);

        addVertex(consumer, matrix, 0, 0, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 0, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 1, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 0, 1, 0, r, g, b, a, light);

        addVertex(consumer, matrix, 1, 0, 1, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 0, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 1, 0, r, g, b, a, light);
        addVertex(consumer, matrix, 1, 1, 1, r, g, b, a, light);
    }

    private void addVertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, int r, int g, int b, int a, int light) {
        consumer.vertex(matrix, x, y, z).color(r, g, b, a).uv(0, 0).overlayCoords(0, 10).uv2(light).normal(0, 1, 0).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(ConstructEntity entity) {
        return GLOW_TEXTURE;
    }
}