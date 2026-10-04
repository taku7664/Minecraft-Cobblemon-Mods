package jbro.cobblemon.battlecam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class BattlecamConfigStore {
    public static final String FILE_NAME = "better_cobblemon_battlecam.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile BattlecamConfig current = BattlecamConfig.defaults();
    private static Path configPath;

    private BattlecamConfigStore() {
    }

    public static void initialize(Path configDirectory) {
        configPath = configDirectory.resolve(FILE_NAME).toAbsolutePath().normalize();
        try {
            if (Files.exists(configPath)) {
                current = load(configPath);
            } else {
                current = BattlecamConfig.defaults();
                save(configPath, current);
            }
        } catch (IOException | RuntimeException exception) {
            current = BattlecamConfig.defaults();
            BetterCobblemonBattlecamClient.LOGGER.error(
                "Could not load Battlecam config {}; using defaults",
                configPath,
                exception
            );
        }
    }

    public static BattlecamConfig current() {
        return current;
    }

    public static Path path() {
        if (configPath == null) {
            throw new IllegalStateException("Battlecam config store has not been initialized");
        }
        return configPath;
    }

    public static void replace(BattlecamConfig config) throws IOException {
        save(path(), config);
        current = config;
    }

    static BattlecamConfig load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return new BattlecamConfig(
                booleanValue(root, "wildEnabled", true),
                modeValue(root, "wildDefaultMode"),
                booleanValue(root, "pveEnabled", true),
                modeValue(root, "pveDefaultMode"),
                booleanValue(root, "pvpEnabled", true),
                modeValue(root, "pvpDefaultMode")
            );
        }
    }

    static void save(Path path, BattlecamConfig config) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Config path has no parent: " + path);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".better_cobblemon_battlecam.", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(config, writer);
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean booleanValue(JsonObject root, String key, boolean defaultValue) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return defaultValue;
        }
        return root.get(key).getAsBoolean();
    }

    private static BattlecamMode modeValue(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return BattlecamMode.AUTO;
        }
        try {
            return BattlecamMode.valueOf(root.get(key).getAsString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return BattlecamMode.AUTO;
        }
    }
}
