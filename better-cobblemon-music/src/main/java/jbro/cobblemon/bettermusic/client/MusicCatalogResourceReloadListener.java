package jbro.cobblemon.bettermusic.client;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jbro.cobblemon.bettermusic.BetterCobblemonMusicClient;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigManager;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

public final class MusicCatalogResourceReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final String CATALOG_ROOT = "catalogs";

    private final BetterMusicConfigManager configManager;
    private final Logger logger;

    public MusicCatalogResourceReloadListener(BetterMusicConfigManager configManager, Logger logger) {
        this.configManager = java.util.Objects.requireNonNull(configManager, "configManager");
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    @Override
    public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath(
            BetterCobblemonMusicClient.MOD_ID,
            "music_catalogs"
        );
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        List<BetterMusicConfigManager.CatalogDocument> documents = new ArrayList<>();
        List<String> readFailures = new ArrayList<>();
        resourceManager.listResourceStacks(CATALOG_ROOT, MusicCatalogResourceReloadListener::isCatalog)
            .entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> readStack(entry.getKey(), entry.getValue(), documents, readFailures));

        var result = configManager.reloadCatalogs(documents);
        for (String failure : readFailures) {
            logger.warn(failure);
        }
        switch (result.outcome()) {
            case APPLIED -> logger.info("{} (revision {})", result.message(), result.revision());
            case RETAINED_LAST_GOOD -> logger.warn("{} (revision {})", result.message(), result.revision());
            case NO_VALID_CONFIG -> logger.error("{} (revision {})", result.message(), result.revision());
            case INITIALIZED -> logger.info(result.message());
        }
    }

    private static boolean isCatalog(ResourceLocation location) {
        if (!location.getNamespace().equals(BetterCobblemonMusicClient.MOD_ID)) {
            return false;
        }
        String path = location.getPath();
        return path.endsWith(".json")
            && (path.startsWith("catalogs/base/") || path.startsWith("catalogs/extensions/"));
    }

    private static void readStack(
        ResourceLocation location,
        List<Resource> resources,
        List<BetterMusicConfigManager.CatalogDocument> documents,
        List<String> failures
    ) {
        for (Resource resource : resources) {
            String source = resource.sourcePackId() + ":" + location;
            try (Reader reader = resource.openAsReader()) {
                documents.add(new BetterMusicConfigManager.CatalogDocument(source, readAll(reader)));
            } catch (IOException exception) {
                failures.add("Could not read music catalog " + source + ": " + exception.getMessage());
            }
        }
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            result.append(buffer, 0, read);
        }
        return result.toString();
    }
}
