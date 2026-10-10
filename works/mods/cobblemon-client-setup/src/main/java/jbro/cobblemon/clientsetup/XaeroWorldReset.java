package jbro.cobblemon.clientsetup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The live server's worlds were reset on 2026-10-11, keeping only the plaza and the same seed. Xaero's cached map
 * tiles and waypoints for that server would still show the old builds and bases, so move them aside once, before
 * Xaero loads. Other servers and single-player maps are left alone; nothing is deleted.
 */
public final class XaeroWorldReset {
    static final String RULE = "xaero-world-reset-2026-10-11";
    /** Xaero's folder names for the live server; a port, when present, follows after an underscore. */
    static final List<String> SERVERS = List.of("Multiplayer_210.207.108.196");
    private static final List<String> KINDS = List.of("minimap", "world-map");

    private XaeroWorldReset() {}

    /** Moves the cached folders to `cobblemon_client_setup/backups/<rule>/` and returns how many moved. */
    public static synchronized int apply(Path gameDirectory, Path configDirectory) throws IOException {
        Properties state = SetupFiles.readState(configDirectory);
        if ("true".equals(state.getProperty(RULE))) return 0;
        Path backup = gameDirectory.resolve("cobblemon_client_setup").resolve("backups").resolve(RULE);
        int moved = 0;
        for (String kind : KINDS) {
            Path directory = gameDirectory.resolve("xaero").resolve(kind);
            if (!Files.isDirectory(directory)) continue;
            List<Path> caches;
            try (Stream<Path> entries = Files.list(directory)) {
                caches = entries.filter(Files::isDirectory).filter(path -> isLiveServer(path.getFileName().toString())).toList();
            }
            for (Path cache : caches) {
                Path target = backup.resolve(kind).resolve(cache.getFileName().toString());
                // A launch that stopped halfway already moved some folders; never overwrite those.
                for (int copy = 2; Files.exists(target); copy++) {
                    target = backup.resolve(kind).resolve(cache.getFileName() + "-" + copy);
                }
                Files.createDirectories(target.getParent());
                Files.move(cache, target);
                moved++;
            }
        }
        SetupFiles.markApplied(configDirectory, state, List.of(RULE));
        return moved;
    }

    static boolean isLiveServer(String folder) {
        return SERVERS.stream().anyMatch(server -> folder.equals(server) || folder.startsWith(server + "_"));
    }
}
