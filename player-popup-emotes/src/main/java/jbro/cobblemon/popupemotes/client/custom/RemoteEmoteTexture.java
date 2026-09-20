package jbro.cobblemon.popupemotes.client.custom;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

final class RemoteEmoteTexture implements AutoCloseable {
    private final DecodedAnimation animation;
    private final NativeImage pixels;
    private final DynamicTexture texture;
    private final ResourceLocation location;
    private final long startedAtNanos = System.nanoTime();
    private int displayedFrame = -1;

    RemoteEmoteTexture(String cacheId, DecodedAnimation animation) {
        this.animation = animation;
        this.pixels = new NativeImage(animation.width(), animation.height(), false);
        writeFrame(animation.frames().getFirst());
        this.texture = new DynamicTexture(pixels);
        this.location = Minecraft.getInstance().getTextureManager().register("player_popup_emotes/remote/" + cacheId, texture);
        this.displayedFrame = 0;
    }

    ResourceLocation location() {
        updateFrame();
        return location;
    }

    long decodedBytes() {
        return animation.decodedBytes();
    }

    private void updateFrame() {
        if (!animation.animated()) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        int nextFrame = frameAt(elapsedMs, animation.frameDurationsMs(), animation.loopCount());
        if (nextFrame == displayedFrame) {
            return;
        }
        writeFrame(animation.frames().get(nextFrame));
        texture.upload();
        displayedFrame = nextFrame;
    }

    static int frameAt(long elapsedMs, List<Integer> delays, int loopCount) {
        long cycleDuration = delays.stream().mapToLong(Integer::longValue).sum();
        if (cycleDuration <= 0L) {
            return 0;
        }
        long withinCycle;
        if (loopCount > 0 && elapsedMs >= cycleDuration * (loopCount + 1L)) {
            return delays.size() - 1;
        }
        withinCycle = Math.floorMod(elapsedMs, cycleDuration);
        long boundary = 0L;
        for (int index = 0; index < delays.size(); index++) {
            boundary += delays.get(index);
            if (withinCycle < boundary) {
                return index;
            }
        }
        return delays.size() - 1;
    }

    private void writeFrame(BufferedImage frame) {
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                int argb = frame.getRGB(x, y);
                int abgr = argb & 0xFF00FF00 | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(location);
    }
}
