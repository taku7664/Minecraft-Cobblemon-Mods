package jbro.minecraft.simpletrashcan.client;

import com.mojang.blaze3d.platform.NativeImage;
import jbro.minecraft.simpletrashcan.TrashSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws the small panel behind the trash slot out of the active resource pack's inventory texture, so it wears the
 * same frame and slot as the inventory beside it.
 */
public final class TrashPanel {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
    private static final Component LABEL = Component.translatable("container.simple_trash_can.trash");
    private static final int SIZE = 256;
    private static final int EDGE = 4;
    // A plain stretch of the side edges, between the crafting result and the inventory rows.
    private static final int SIDE_V = 62;
    private static final int SIDE_HEIGHT = 16;
    // Vanilla's armor slot frame.
    private static final int SLOT_U = 7;
    private static final int SLOT_V = 7;
    private static final int SLOT = 18;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final Skin VANILLA = new Skin(176, 166, 0xFFC6C6C6);

    private record Skin(int width, int height, int bodyColor) {
    }

    private static Skin skin;

    private TrashPanel() {
    }

    public static boolean contains(int left, int top, double mouseX, double mouseY) {
        double x = mouseX - left - TrashSlot.PANEL_X;
        double y = mouseY - top - TrashSlot.PANEL_Y;
        return x >= 0 && y >= 0 && x < TrashSlot.PANEL_WIDTH && y < TrashSlot.PANEL_HEIGHT;
    }

    public static void render(GuiGraphics graphics, int left, int top) {
        Skin skin = skin();
        int x = left + TrashSlot.PANEL_X;
        int y = top + TrashSlot.PANEL_Y;
        int w = TrashSlot.PANEL_WIDTH;
        int h = TrashSlot.PANEL_HEIGHT;
        int right = skin.width() - EDGE;
        int bottom = skin.height() - EDGE;
        graphics.fill(x + EDGE, y + EDGE, x + w - EDGE, y + h - EDGE, skin.bodyColor());
        tiled(graphics, x + EDGE, y, w - EDGE * 2, EDGE, 8, 0, 32, EDGE);
        tiled(graphics, x + EDGE, y + h - EDGE, w - EDGE * 2, EDGE, 8, bottom, 32, EDGE);
        tiled(graphics, x, y + EDGE, EDGE, h - EDGE * 2, 0, SIDE_V, EDGE, SIDE_HEIGHT);
        tiled(graphics, x + w - EDGE, y + EDGE, EDGE, h - EDGE * 2, right, SIDE_V, EDGE, SIDE_HEIGHT);
        blit(graphics, x, y, 0, 0, EDGE, EDGE);
        blit(graphics, x + w - EDGE, y, right, 0, EDGE, EDGE);
        blit(graphics, x, y + h - EDGE, 0, bottom, EDGE, EDGE);
        blit(graphics, x + w - EDGE, y + h - EDGE, right, bottom, EDGE, EDGE);
        blit(graphics, left + TrashSlot.X - 1, top + TrashSlot.Y - 1, SLOT_U, SLOT_V, SLOT, SLOT);
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, LABEL, x + (w - font.width(LABEL)) / 2, y + 6, LABEL_COLOR, false);
    }

    public static void invalidate() {
        skin = null;
    }

    private static void blit(GuiGraphics graphics, int x, int y, int u, int v, int width, int height) {
        if (width > 0 && height > 0) graphics.blit(TEXTURE, x, y, u, v, width, height, SIZE, SIZE);
    }

    /** Fills a [width] by [height] area by repeating the texture piece at ([u], [v]). */
    private static void tiled(GuiGraphics graphics, int x, int y, int width, int height, int u, int v, int pieceWidth, int pieceHeight) {
        for (int dy = 0; dy < height; dy += pieceHeight) {
            int ph = Math.min(pieceHeight, height - dy);
            for (int dx = 0; dx < width; dx += pieceWidth) {
                blit(graphics, x + dx, y + dy, u, v, Math.min(pieceWidth, width - dx), ph);
            }
        }
    }

    private static Skin skin() {
        if (skin == null) skin = measure();
        return skin;
    }

    /**
     * Resource packs keep vanilla's layout but move the edges by a pixel or two, so the panel's size is the last opaque
     * pixel on a row and a column through its top section, and its body colour is read beside the inventory rows.
     */
    private static Skin measure() {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(TEXTURE).orElse(null);
            if (resource == null) return VANILLA;
            try (var stream = resource.open(); NativeImage image = NativeImage.read(stream)) {
                if (image.getWidth() != SIZE || image.getHeight() != SIZE) return VANILLA;
                int width = 0;
                int height = 0;
                for (int i = SIZE - 1; i >= 0 && width == 0; i--) if ((image.getPixelRGBA(i, 40) >>> 24) != 0) width = i + 1;
                for (int i = SIZE - 1; i >= 0 && height == 0; i--) if ((image.getPixelRGBA(40, i) >>> 24) != 0) height = i + 1;
                if (width < 100 || height < 100) return VANILLA;
                // NativeImage pixels are ABGR; fills want ARGB.
                int abgr = image.getPixelRGBA(width - 10, 68);
                int argb = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
                return new Skin(width, height, argb);
            }
        } catch (Exception failure) {
            return VANILLA;
        }
    }
}
