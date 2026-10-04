package jbro.cobblemon.popupemotes.client.custom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import jbro.cobblemon.popupemotes.PlayerPopupEmotes;
import net.fabricmc.loader.api.FabricLoader;

public final class CustomEmoteConfigStore {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("player-popup-emotes.json");
    private static volatile CustomEmoteCatalog catalog = new CustomEmoteCatalog(List.of());

    private CustomEmoteConfigStore() {
    }

    public static void load() {
        if (!Files.isRegularFile(PATH)) {
            catalog = new CustomEmoteCatalog(List.of());
            return;
        }
        try {
            catalog = new CustomEmoteCatalog(CustomEmoteConfigCodec.decode(Files.readString(PATH, StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            PlayerPopupEmotes.LOGGER.error("Could not load custom emotes from {}", PATH, exception);
            catalog = new CustomEmoteCatalog(List.of());
        }
    }

    public static void save(List<CustomEmoteDefinition> entries) throws IOException {
        var next = new CustomEmoteCatalog(entries);
        Files.createDirectories(PATH.getParent());
        Path staging = PATH.resolveSibling(PATH.getFileName() + ".saving");
        Files.writeString(staging, CustomEmoteConfigCodec.encode(next.entries()), StandardCharsets.UTF_8);
        try {
            Files.move(staging, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(staging, PATH, StandardCopyOption.REPLACE_EXISTING);
        }
        catalog = next;
    }

    public static CustomEmoteCatalog catalog() {
        return catalog;
    }
}
