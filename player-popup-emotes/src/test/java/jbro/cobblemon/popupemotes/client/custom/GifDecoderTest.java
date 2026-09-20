package jbro.cobblemon.popupemotes.client.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class GifDecoderTest {
    private static final byte[] TWO_FRAME_GIF = Base64.getDecoder().decode(
        "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQIBQAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQICgAAACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7"
    );

    @Test
    void preservesGifFramesDelaysAndInfiniteLooping() throws Exception {
        DecodedAnimation animation = RemoteImageDecoder.decode(TWO_FRAME_GIF);

        assertEquals(2, animation.width());
        assertEquals(2, animation.height());
        assertEquals(2, animation.frames().size());
        assertEquals(50, animation.frameDurationsMs().get(0));
        assertEquals(100, animation.frameDurationsMs().get(1));
        assertEquals(0, animation.loopCount());
        assertEquals(0xFFFF0000, animation.frames().get(0).getRGB(0, 0));
        assertEquals(0xFF0000FF, animation.frames().get(1).getRGB(0, 0));
    }

    @Test
    void choosesFramesUsingTheirIndividualDurations() {
        var delays = java.util.List.of(50, 100);

        assertEquals(0, RemoteEmoteTexture.frameAt(0, delays, 0));
        assertEquals(0, RemoteEmoteTexture.frameAt(49, delays, 0));
        assertEquals(1, RemoteEmoteTexture.frameAt(50, delays, 0));
        assertEquals(1, RemoteEmoteTexture.frameAt(149, delays, 0));
        assertEquals(0, RemoteEmoteTexture.frameAt(150, delays, 0));
    }

    @Test
    void scalesLargeGifFramesToTheSafeRenderSize() throws Exception {
        var source = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(599, 299, 0xFFFF0000);
        var output = new ByteArrayOutputStream();
        ImageIO.write(source, "gif", output);

        DecodedAnimation animation = RemoteImageDecoder.decode(output.toByteArray());

        assertEquals(512, animation.width());
        assertEquals(256, animation.height());
        assertEquals(1, animation.frames().size());
    }
}
