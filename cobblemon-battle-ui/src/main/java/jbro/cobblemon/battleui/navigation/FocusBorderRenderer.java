package jbro.cobblemon.battleui.navigation;

import net.minecraft.client.gui.DrawContext;

public final class FocusBorderRenderer {
    private static final double REST_SCALE = 1.0;
    private static final double FOCUSED_SCALE = 1.06;
    private static final int MAX_ALPHA = 0xF2;
    private static final int BORDER_THICKNESS = 2;

    private FocusBorderRenderer() {
    }

    public static void draw(
            DrawContext context,
            float x,
            float y,
            int width,
            int height,
            double currentScale
    ) {
        int alpha = alphaForScale(currentScale);
        if (alpha == 0) {
            return;
        }

        int left = Math.round(x);
        int top = Math.round(y);
        int right = left + width;
        int bottom = top + height;
        int color = alpha << 24 | 0x00FFFFFF;

        context.fill(left - BORDER_THICKNESS, top - BORDER_THICKNESS, right + BORDER_THICKNESS, top, color);
        context.fill(left - BORDER_THICKNESS, bottom, right + BORDER_THICKNESS, bottom + BORDER_THICKNESS, color);
        context.fill(left - BORDER_THICKNESS, top, left, bottom, color);
        context.fill(right, top, right + BORDER_THICKNESS, bottom, color);
    }

    static int alphaForScale(double scale) {
        double progress = (scale - REST_SCALE) / (FOCUSED_SCALE - REST_SCALE);
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return (int) Math.round(MAX_ALPHA * clamped);
    }
}
