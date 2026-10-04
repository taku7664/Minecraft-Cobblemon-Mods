package jbro.cobblemon.popupemotes.client.custom;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class RemoteImageDecoder {
    public static final int MAX_DIMENSION = 512;
    public static final int MAX_SOURCE_DIMENSION = 2048;
    public static final long MAX_SOURCE_PIXELS = 4_194_304L;
    public static final int MAX_FRAMES = 120;
    public static final long MAX_DECODED_PIXELS = 16_000_000L;
    public static final int MIN_FRAME_DELAY_MS = 20;

    private RemoteImageDecoder() {
    }

    public static DecodedAnimation decode(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Unsupported image data");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Only PNG, JPEG, and GIF images are supported");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                String format = reader.getFormatName();
                if ("gif".equalsIgnoreCase(format)) {
                    return decodeGif(reader);
                }
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format) && !"jpg".equalsIgnoreCase(format)) {
                    throw new IOException("Only PNG, JPEG, and GIF images are supported");
                }
                BufferedImage image = reader.read(0);
                validateSourceDimensions(image.getWidth(), image.getHeight());
                Dimensions target = targetDimensions(image.getWidth(), image.getHeight());
                validateDecodedSize(target.width, target.height, 1);
                return new DecodedAnimation(
                    target.width,
                    target.height,
                    List.of(scale(image, target.width, target.height)),
                    List.of(Integer.MAX_VALUE),
                    1
                );
            } finally {
                reader.dispose();
            }
        }
    }

    private static DecodedAnimation decodeGif(ImageReader reader) throws IOException {
        int frameCount = reader.getNumImages(true);
        if (frameCount <= 0 || frameCount > MAX_FRAMES) {
            throw new IOException("GIF must contain 1-" + MAX_FRAMES + " frames");
        }
        int width = logicalSize(reader.getStreamMetadata(), "logicalScreenWidth", reader.getWidth(0));
        int height = logicalSize(reader.getStreamMetadata(), "logicalScreenHeight", reader.getHeight(0));
        validateSourceDimensions(width, height);
        Dimensions target = targetDimensions(width, height);
        validateDecodedSize(target.width, target.height, frameCount);

        var frames = new ArrayList<BufferedImage>(frameCount);
        var delays = new ArrayList<Integer>(frameCount);
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        BufferedImage restoreCanvas = null;
        FrameMeta previous = null;
        for (int index = 0; index < frameCount; index++) {
            if (previous != null) {
                if ("restoreToBackgroundColor".equals(previous.disposal)) {
                    clear(canvas, previous.x, previous.y, previous.width, previous.height);
                } else if ("restoreToPrevious".equals(previous.disposal) && restoreCanvas != null) {
                    canvas = copy(restoreCanvas);
                }
            }

            FrameMeta meta = frameMeta(reader.getImageMetadata(index), reader.getWidth(index), reader.getHeight(index));
            restoreCanvas = "restoreToPrevious".equals(meta.disposal) ? copy(canvas) : null;
            BufferedImage raw = reader.read(index);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(raw, meta.x, meta.y, null);
            } finally {
                graphics.dispose();
            }
            frames.add(scale(canvas, target.width, target.height));
            delays.add(Math.max(MIN_FRAME_DELAY_MS, meta.delayMs));
            previous = meta;
        }
        int loopCount = loopCount(reader.getStreamMetadata());
        if (loopCount == 1) {
            loopCount = loopCount(reader.getImageMetadata(0));
        }
        return new DecodedAnimation(target.width, target.height, frames, delays, loopCount);
    }

    private static void validateSourceDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) {
            throw new IOException("Source image dimensions must be at most " + MAX_SOURCE_DIMENSION + "x" + MAX_SOURCE_DIMENSION);
        }
        if ((long)width * height > MAX_SOURCE_PIXELS) {
            throw new IOException("Source image is too large");
        }
    }

    private static void validateDecodedSize(int width, int height, int frames) throws IOException {
        if ((long)width * height * frames > MAX_DECODED_PIXELS) {
            throw new IOException("Decoded GIF is too large");
        }
    }

    private static Dimensions targetDimensions(int width, int height) {
        double scale = Math.min(1.0, (double)MAX_DIMENSION / Math.max(width, height));
        return new Dimensions(
            Math.max(1, (int)Math.round(width * scale)),
            Math.max(1, (int)Math.round(height * scale))
        );
    }

    private static FrameMeta frameMeta(IIOMetadata metadata, int fallbackWidth, int fallbackHeight) {
        int x = 0;
        int y = 0;
        int width = fallbackWidth;
        int height = fallbackHeight;
        int delayMs = 100;
        String disposal = "none";
        try {
            Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
            IIOMetadataNode descriptor = first(root, "ImageDescriptor");
            if (descriptor != null) {
                x = integer(descriptor, "imageLeftPosition", 0);
                y = integer(descriptor, "imageTopPosition", 0);
                width = integer(descriptor, "imageWidth", fallbackWidth);
                height = integer(descriptor, "imageHeight", fallbackHeight);
            }
            IIOMetadataNode control = first(root, "GraphicControlExtension");
            if (control != null) {
                delayMs = integer(control, "delayTime", 10) * 10;
                disposal = control.getAttribute("disposalMethod");
            }
        } catch (RuntimeException ignored) {
        }
        return new FrameMeta(x, y, width, height, delayMs, disposal);
    }

    private static int loopCount(IIOMetadata metadata) {
        if (metadata == null || metadata.getNativeMetadataFormatName() == null) {
            return 1;
        }
        try {
            Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
            NodeList extensions = ((IIOMetadataNode)root).getElementsByTagName("ApplicationExtension");
            for (int index = 0; index < extensions.getLength(); index++) {
                Object value = ((IIOMetadataNode)extensions.item(index)).getUserObject();
                if (value instanceof byte[] bytes && bytes.length >= 3 && bytes[0] == 1) {
                    return (bytes[1] & 0xFF) | (bytes[2] & 0xFF) << 8;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return 1;
    }

    private static int logicalSize(IIOMetadata metadata, String attribute, int fallback) {
        if (metadata == null || metadata.getNativeMetadataFormatName() == null) {
            return fallback;
        }
        try {
            Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
            IIOMetadataNode descriptor = first(root, "LogicalScreenDescriptor");
            return descriptor == null ? fallback : integer(descriptor, attribute, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static IIOMetadataNode first(Node root, String name) {
        NodeList nodes = ((IIOMetadataNode)root).getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : (IIOMetadataNode)nodes.item(0);
    }

    private static int integer(IIOMetadataNode node, String name, int fallback) {
        try {
            return Integer.parseInt(node.getAttribute(name));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void clear(BufferedImage image, int x, int y, int width, int height) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(x, y, width, height);
        } finally {
            graphics.dispose();
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage toArgb(BufferedImage source) {
        return source.getType() == BufferedImage.TYPE_INT_ARGB ? source : copy(source);
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return copy(source);
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private record Dimensions(int width, int height) {
    }

    private record FrameMeta(int x, int y, int width, int height, int delayMs, String disposal) {
    }
}
