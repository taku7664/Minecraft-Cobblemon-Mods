package jbro.cobblemon.popupemotes.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

final class EmoteWheelRenderer {
    private static final double INNER_RADIUS = 32.0;
    private static final double OUTER_RADIUS = 94.0;
    private static final double ICON_RADIUS = 63.0;
    private static final double SLICE_GAP = Math.toRadians(1.8);
    private static final int ARC_SEGMENTS = 10;
    private static final float GAMEPLAY_ALPHA = 0.55F;

    private static final int BACKDROP_COLOR = 0xDC11151D;
    private static final int SLICE_COLOR = 0xD82A303C;
    private static final int CENTER_COLOR = 0xED202632;

    private EmoteWheelRenderer() {
    }

    static void render(
        GuiGraphics graphics,
        int centerX,
        int centerY,
        int selectedIndex,
        List<WheelEmote> emotes
    ) {
        render(graphics, centerX, centerY, selectedIndex, -1, emotes, 1.0F, GAMEPLAY_ALPHA);
    }

    static void render(
        GuiGraphics graphics,
        int centerX,
        int centerY,
        int selectedIndex,
        List<WheelEmote> emotes,
        float scale
    ) {
        render(graphics, centerX, centerY, selectedIndex, -1, emotes, scale, 1.0F);
    }

    static void render(
        GuiGraphics graphics,
        int centerX,
        int centerY,
        int selectedIndex,
        int hoveredIndex,
        List<WheelEmote> emotes,
        float scale
    ) {
        render(graphics, centerX, centerY, selectedIndex, hoveredIndex, emotes, scale, 1.0F);
    }

    private static void render(
        GuiGraphics graphics,
        int centerX,
        int centerY,
        int selectedIndex,
        int hoveredIndex,
        List<WheelEmote> emotes,
        float scale,
        float alphaMultiplier
    ) {
        if (emotes.isEmpty()) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate(-centerX, -centerY, 0.0F);
        int outerCount = emotes.size() - 1;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = graphics.pose().last().pose();

        double sliceAngle = outerCount == 0 ? 0.0 : Math.PI * 2.0 / outerCount;
        if (outerCount > 0) {
            drawCircle(
                matrix,
                centerX,
                centerY,
                OUTER_RADIUS + 3.0,
                EmoteWheelHighlight.withAlpha(BACKDROP_COLOR, alphaMultiplier),
                64
            );
            for (int index = 0; index < outerCount; index++) {
                double middle = -Math.PI / 2.0 + index * sliceAngle;
                int color = EmoteWheelHighlight.colorFor(index, selectedIndex, hoveredIndex, SLICE_COLOR);
                drawRingSlice(
                    matrix,
                    centerX,
                    centerY,
                    INNER_RADIUS,
                    OUTER_RADIUS,
                    middle - sliceAngle / 2.0 + SLICE_GAP,
                    middle + sliceAngle / 2.0 - SLICE_GAP,
                    EmoteWheelHighlight.withAlpha(color, alphaMultiplier)
                );
            }
        }

        int centerIndex = outerCount;
        int centerColor = EmoteWheelHighlight.colorFor(centerIndex, selectedIndex, hoveredIndex, CENTER_COLOR);
        drawCircle(
            matrix,
            centerX,
            centerY,
            INNER_RADIUS - 3.0,
            EmoteWheelHighlight.withAlpha(centerColor, alphaMultiplier),
            32
        );
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        for (int index = 0; index < outerCount; index++) {
            double angle = -Math.PI / 2.0 + index * sliceAngle;
            int iconX = centerX + (int)Math.round(Math.cos(angle) * ICON_RADIUS);
            int iconY = centerY + (int)Math.round(Math.sin(angle) * ICON_RADIUS);
            drawIcon(graphics, emotes.get(index), iconX, iconY);
        }
        drawIcon(graphics, emotes.get(centerIndex), centerX, centerY);
        graphics.pose().popPose();
    }

    private static void drawIcon(GuiGraphics graphics, WheelEmote emote, int x, int y) {
        graphics.blit(emote.texture().get(), x - 8, y - 8, 0.0F, 0.0F, 16, 16, 16, 16);
    }

    private static void drawCircle(
        Matrix4f matrix,
        double centerX,
        double centerY,
        double radius,
        int color,
        int segments
    ) {
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int segment = 0; segment < segments; segment++) {
            double first = Math.PI * 2.0 * segment / segments;
            double second = Math.PI * 2.0 * (segment + 1) / segments;
            vertex(builder, matrix, centerX, centerY, color);
            vertex(builder, matrix, centerX + Math.cos(first) * radius, centerY + Math.sin(first) * radius, color);
            vertex(builder, matrix, centerX + Math.cos(second) * radius, centerY + Math.sin(second) * radius, color);
        }
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void drawRingSlice(
        Matrix4f matrix,
        double centerX,
        double centerY,
        double innerRadius,
        double outerRadius,
        double startAngle,
        double endAngle,
        int color
    ) {
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int segment = 0; segment < ARC_SEGMENTS; segment++) {
            double first = startAngle + (endAngle - startAngle) * segment / ARC_SEGMENTS;
            double second = startAngle + (endAngle - startAngle) * (segment + 1) / ARC_SEGMENTS;
            vertex(builder, matrix, centerX + Math.cos(first) * innerRadius, centerY + Math.sin(first) * innerRadius, color);
            vertex(builder, matrix, centerX + Math.cos(first) * outerRadius, centerY + Math.sin(first) * outerRadius, color);
            vertex(builder, matrix, centerX + Math.cos(second) * outerRadius, centerY + Math.sin(second) * outerRadius, color);
            vertex(builder, matrix, centerX + Math.cos(first) * innerRadius, centerY + Math.sin(first) * innerRadius, color);
            vertex(builder, matrix, centerX + Math.cos(second) * outerRadius, centerY + Math.sin(second) * outerRadius, color);
            vertex(builder, matrix, centerX + Math.cos(second) * innerRadius, centerY + Math.sin(second) * innerRadius, color);
        }
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void vertex(
        com.mojang.blaze3d.vertex.BufferBuilder builder,
        Matrix4f matrix,
        double x,
        double y,
        int color
    ) {
        builder.addVertex(matrix, (float)x, (float)y, 0.0F).setColor(
            color >> 16 & 0xFF,
            color >> 8 & 0xFF,
            color & 0xFF,
            color >>> 24
        );
    }
}
