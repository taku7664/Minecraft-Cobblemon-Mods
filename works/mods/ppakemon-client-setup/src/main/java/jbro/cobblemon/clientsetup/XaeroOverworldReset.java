package jbro.cobblemon.clientsetup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/** Moves only the live server's overworld maps aside before Xaero loads. */
public final class XaeroOverworldReset {
    static final String RULE = "xaero-overworld-reset-2026-11-11";
    private static final List<String> SERVERS = List.of("Multiplayer_210.207.108.196");

    private XaeroOverworldReset() {}

    public static synchronized int apply(Path gameDirectory, Path configDirectory) throws IOException {
        Properties state = SetupFiles.readState(configDirectory);
        if ("true".equals(state.getProperty(RULE))) return 0;
        // A profile copied after this reset carries maps that were already generated for the new overworld.
        if ("true".equals(SetupFiles.readRawState(configDirectory).getProperty(RULE))) {
            SetupFiles.markApplied(configDirectory, state, List.of(RULE));
            return 0;
        }

        Path backup = gameDirectory.resolve("ppakemon_client_setup/backups").resolve(RULE);
        int moved = 0;
        moved += moveOverworld(gameDirectory, backup, "minimap", "dim%0");
        moved += moveOverworld(gameDirectory, backup, "world-map", "null");
        SetupFiles.markApplied(configDirectory, state, List.of(RULE));
        return moved;
    }

    private static int moveOverworld(Path gameDirectory, Path backup, String kind, String dimension)
        throws IOException {
        Path root = gameDirectory.resolve("xaero").resolve(kind);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        List<Path> servers;
        try (Stream<Path> entries = Files.list(root)) {
            servers = entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> isLiveServer(path.getFileName().toString())).toList();
        }
        int moved = 0;
        for (Path server : servers) {
            Path source = server.resolve(dimension);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) continue;
            Path target = backup.resolve(kind).resolve(server.getFileName().toString()).resolve(dimension);
            for (int copy = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); copy++) {
                target = backup.resolve(kind).resolve(server.getFileName().toString()).resolve(dimension + "-" + copy);
            }
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            moved++;
        }
        return moved;
    }

    private static boolean isLiveServer(String folder) {
        return SERVERS.stream().anyMatch(server -> folder.equals(server) || folder.startsWith(server + "_"));
    }
}
