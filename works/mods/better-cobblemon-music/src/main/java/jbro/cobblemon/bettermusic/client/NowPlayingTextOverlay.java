package jbro.cobblemon.bettermusic.client;

import jbro.cobblemon.bettermusic.playback.NowPlayingAnnouncement;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Draws only text; does not create a screen, panel, input handler, or toast. */
public final class NowPlayingTextOverlay {
    private final NowPlayingAnnouncement announcement = new NowPlayingAnnouncement();

    public void register() {
        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
            var client = Minecraft.getInstance();
            if (client.screen == null) {
                render(client, graphics);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterRender(screen).register((renderedScreen, graphics, mouseX, mouseY, delta) ->
                render(client, graphics)));
    }

    public void setEnabled(boolean enabled) {
        announcement.setEnabled(enabled);
    }

    public void trackStarted(String sound, String title) {
        announcement.trackStarted(sound, title, seconds());
    }

    public void clear() {
        announcement.clear();
    }

    private void render(Minecraft client, GuiGraphics graphics) {
        if (client.options.hideGui) {
            return;
        }
        announcement.frame(seconds()).ifPresent(frame -> {
            int alpha = (int) Math.round(frame.opacity() * 255.0);
            // Minecraft's font treats near-zero alpha as an unspecified (opaque) color.
            if (alpha < 4) {
                return;
            }
            int available = Math.max(0, graphics.guiWidth() - 16);
            String title = client.font.plainSubstrByWidth(frame.title(), available);
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(frame.offsetX(), 0.0, 1000.0);
                graphics.drawString(client.font, title, 8, 8, (alpha << 24) | 0xFFFFFF, true);
            } finally {
                graphics.pose().popPose();
            }
        });
    }

    private static double seconds() {
        return Util.getMillis() / 1000.0;
    }
}
