package jbro.cobblemon.popupemotes.client.custom;

import java.awt.image.BufferedImage;
import java.util.List;

public record DecodedAnimation(
    int width,
    int height,
    List<BufferedImage> frames,
    List<Integer> frameDurationsMs,
    int loopCount
) {
    public DecodedAnimation {
        frames = List.copyOf(frames);
        frameDurationsMs = List.copyOf(frameDurationsMs);
        if (width <= 0 || height <= 0 || frames.isEmpty() || frames.size() != frameDurationsMs.size()) {
            throw new IllegalArgumentException("Invalid decoded animation");
        }
    }

    public long decodedBytes() {
        return (long)width * height * 4L * frames.size();
    }

    public boolean animated() {
        return frames.size() > 1;
    }
}
