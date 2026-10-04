package jbro.cobblemon.popupemotes.client.custom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jbro.cobblemon.popupemotes.PlayerPopupEmotes;
import jbro.cobblemon.popupemotes.emote.RemoteEmoteUrlPolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

public final class RemoteEmoteManager {
    private static final long MAX_MEMORY_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DISK_BYTES = 64L * 1024L * 1024L;
    private static final ResourceLocation LOADING = ResourceLocation.withDefaultNamespace("textures/item/ender_eye.png");
    private static final ResourceLocation FAILED = ResourceLocation.withDefaultNamespace("textures/item/barrier.png");
    private static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve("cache/player-popup-emotes");
    private static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "player-popup-emotes-downloader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static long memoryBytes;

    private RemoteEmoteManager() {
    }

    public static synchronized ResourceLocation texture(String url) {
        if (!RemoteEmoteUrlPolicy.acceptsSyntax(url) || RemoteEmoteUrlPolicy.isKnownWebPage(url)) {
            return FAILED;
        }
        CacheEntry entry = CACHE.get(url);
        if (entry == null) {
            entry = new CacheEntry();
            CACHE.put(url, entry);
            startLoad(url, entry);
        }
        if (entry.texture != null) {
            return entry.texture.location();
        }
        return entry.failed ? FAILED : LOADING;
    }

    public static synchronized boolean isReady(String url) {
        CacheEntry entry = CACHE.get(url);
        return entry != null && entry.texture != null;
    }

    public static synchronized void retry(String url) {
        CacheEntry old = CACHE.remove(url);
        if (old != null && old.texture != null) {
            memoryBytes -= old.texture.decodedBytes();
            old.texture.close();
        }
        texture(url);
    }

    public static synchronized void closeAll() {
        for (CacheEntry entry : CACHE.values()) {
            if (entry.texture != null) {
                entry.texture.close();
            }
        }
        CACHE.clear();
        memoryBytes = 0L;
    }

    private static void startLoad(String url, CacheEntry entry) {
        entry.loading = CompletableFuture.supplyAsync(() -> loadBytes(url), DOWNLOADS)
            .thenApply(bytes -> {
                try {
                    return RemoteImageDecoder.decode(bytes);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        entry.loading.whenComplete((animation, failure) -> Minecraft.getInstance().execute(() -> finish(url, entry, animation, failure)));
    }

    private static byte[] loadBytes(String url) {
        try {
            Files.createDirectories(CACHE_DIR);
            Path path = CACHE_DIR.resolve(hash(url) + ".image");
            if (Files.isRegularFile(path) && Files.size(path) <= RemoteEmoteDownloader.MAX_FILE_BYTES) {
                Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                return Files.readAllBytes(path);
            }
            byte[] bytes = RemoteEmoteDownloader.download(url);
            Files.write(path, bytes);
            trimDiskCache();
            return bytes;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(exception);
        }
    }

    private static synchronized void finish(
        String url,
        CacheEntry expected,
        DecodedAnimation animation,
        Throwable failure
    ) {
        if (CACHE.get(url) != expected) {
            return;
        }
        expected.loading = null;
        if (failure != null) {
            expected.failed = true;
            CompletableFuture.runAsync(() -> deleteCached(url), DOWNLOADS);
            PlayerPopupEmotes.LOGGER.warn("Could not load custom emote {}: {}", url, rootMessage(failure));
            return;
        }
        try {
            RemoteEmoteTexture texture = new RemoteEmoteTexture(hash(url), animation);
            evictFor(texture.decodedBytes(), url);
            expected.texture = texture;
            memoryBytes += texture.decodedBytes();
        } catch (RuntimeException exception) {
            expected.failed = true;
            PlayerPopupEmotes.LOGGER.warn("Could not create custom emote texture {}", url, exception);
        }
    }

    private static void evictFor(long incomingBytes, String retainedUrl) {
        var iterator = CACHE.entrySet().iterator();
        while (memoryBytes + incomingBytes > MAX_MEMORY_BYTES && iterator.hasNext()) {
            var candidate = iterator.next();
            if (candidate.getKey().equals(retainedUrl) || candidate.getValue().texture == null) {
                continue;
            }
            memoryBytes -= candidate.getValue().texture.decodedBytes();
            candidate.getValue().texture.close();
            iterator.remove();
        }
    }

    private static void trimDiskCache() throws IOException {
        var files = new ArrayList<Path>();
        try (var stream = Files.list(CACHE_DIR)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparingLong(RemoteEmoteManager::modifiedTime));
        long total = 0L;
        for (Path file : files) {
            total += Files.size(file);
        }
        for (Path file : files) {
            if (total <= MAX_DISK_BYTES) {
                break;
            }
            long size = Files.size(file);
            Files.deleteIfExists(file);
            total -= size;
        }
    }

    private static long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static void deleteCached(String url) {
        try {
            Files.deleteIfExists(CACHE_DIR.resolve(hash(url) + ".image"));
        } catch (IOException exception) {
            PlayerPopupEmotes.LOGGER.debug("Could not remove failed custom emote cache {}", url, exception);
        }
    }

    private static final class CacheEntry {
        private CompletableFuture<DecodedAnimation> loading;
        private RemoteEmoteTexture texture;
        private boolean failed;
    }
}
