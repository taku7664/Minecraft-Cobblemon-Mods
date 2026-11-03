package jbro.cobblemon.battlespeed.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Each player's chosen level, kept with the world rather than in config/. */
public final class PlayerSpeedStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, Integer> levels = new ConcurrentHashMap<>();
    private final Path file;

    public PlayerSpeedStore(Path file) {
        this.file = file;
    }

    public Optional<Integer> level(UUID player) {
        return Optional.ofNullable(levels.get(player));
    }

    public void set(UUID player, int level) {
        levels.put(player, level);
    }

    public void load() throws IOException {
        levels.clear();
        if (file == null || Files.notExists(file)) {
            return;
        }
        Map<String, Integer> raw;
        try {
            raw = GSON.fromJson(
                Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Integer>>() { }.getType()
            );
        } catch (JsonParseException exception) {
            throw new IOException("broken player speed file " + file + ": " + exception.getMessage(), exception);
        }
        if (raw == null) {
            return;
        }
        raw.forEach((id, level) -> {
            if (level == null) {
                return;
            }
            try {
                levels.put(UUID.fromString(id), level);
            } catch (IllegalArgumentException ignored) {
                // A hand-edited line with a bad UUID is skipped rather than dropping everyone's choice.
            }
        });
    }

    public void save() throws IOException {
        if (file == null) {
            return;
        }
        var sorted = new TreeMap<String, Integer>();
        levels.forEach((id, level) -> sorted.put(id.toString(), level));
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(sorted), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
