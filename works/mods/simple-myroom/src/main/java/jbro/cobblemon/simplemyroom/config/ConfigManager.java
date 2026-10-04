package jbro.cobblemon.simplemyroom.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path configPath;
    private final Path messagesPath;
    private volatile SimpleMyRoomConfig config = SimpleMyRoomConfig.defaults();
    private volatile SimpleMyRoomMessages messages = new SimpleMyRoomMessages();

    public ConfigManager() {
        this(FabricLoader.getInstance().getConfigDir());
    }

    ConfigManager(Path configDirectory) {
        this.configPath = configDirectory.resolve("simple-myroom.json");
        this.messagesPath = configDirectory.resolve("simple-myroom-messages.json");
    }

    public synchronized ReloadResult load() {
        try {
            Files.createDirectories(configPath.getParent());
            SimpleMyRoomConfig candidate = readOrCreate(configPath, SimpleMyRoomConfig.class, SimpleMyRoomConfig.defaults());
            candidate.normalize();
            List<String> errors = candidate.validate();
            if (!errors.isEmpty()) {
                return ReloadResult.failure(String.join(" ", errors));
            }
            SimpleMyRoomMessages candidateMessages = readOrCreate(messagesPath, SimpleMyRoomMessages.class, new SimpleMyRoomMessages());
            candidateMessages.normalize();
            updateConfigFile(configPath, SimpleMyRoomConfig.defaults());
            updateMessagesFile(messagesPath, new SimpleMyRoomMessages());
            config = candidate;
            messages = candidateMessages;
            return ReloadResult.success();
        } catch (IOException | RuntimeException exception) {
            return ReloadResult.failure(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    public SimpleMyRoomConfig config() {
        return config;
    }

    public SimpleMyRoomMessages messages() {
        return messages;
    }

    public Path configPath() {
        return configPath;
    }

    public Path messagesPath() {
        return messagesPath;
    }

    private static <T> T readOrCreate(Path path, Class<T> type, T defaults) throws IOException {
        if (!Files.exists(path)) {
            writeAtomically(path, GSON.toJson(defaults));
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) throw new JsonParseException(path.getFileName() + " is empty.");
            return value;
        }
    }

    private static void writeAtomically(Path path, String json) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void updateConfigFile(Path path, SimpleMyRoomConfig defaults) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) return;
        JsonObject target = parsed.getAsJsonObject();
        boolean changed = mergeMissing(target, GSON.toJsonTree(defaults).getAsJsonObject());
        JsonObject commands = target.has("commands") && target.get("commands").isJsonObject()
            ? target.getAsJsonObject("commands") : null;
        JsonObject returnBehavior = target.has("returnBehavior") && target.get("returnBehavior").isJsonObject()
            ? target.getAsJsonObject("returnBehavior") : null;
        changed |= commands != null && commands.remove("enableDimensionAdminCommands") != null;
        changed |= returnBehavior != null && returnBehavior.remove("defaultAllowedDimensions") != null;
        changed |= returnBehavior != null && returnBehavior.remove("clearPointWhenSourceDimensionIsNotAllowed") != null;
        if (changed) writeAtomically(path, GSON.toJson(target));
    }

    private static void updateMessagesFile(Path path, SimpleMyRoomMessages defaults) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) return;
        JsonObject target = parsed.getAsJsonObject();
        boolean changed = mergeMissing(target, GSON.toJsonTree(defaults).getAsJsonObject());
        for (String legacyKey : List.of(
            "returnDimensionNotSaved",
            "returnDimensionNoLongerAllowed",
            "invalidDimension",
            "dimensionAdded",
            "dimensionAlreadyAdded",
            "dimensionRemoved",
            "dimensionNotListed",
            "dimensions"
        )) {
            changed |= target.remove(legacyKey) != null;
        }
        if (changed) writeAtomically(path, GSON.toJson(target));
    }

    private static boolean mergeMissing(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (var entry : defaults.entrySet()) {
            if (!target.has(entry.getKey())) {
                target.add(entry.getKey(), entry.getValue().deepCopy());
                changed = true;
            } else if (target.get(entry.getKey()).isJsonObject() && entry.getValue().isJsonObject()) {
                changed |= mergeMissing(target.getAsJsonObject(entry.getKey()), entry.getValue().getAsJsonObject());
            }
        }
        return changed;
    }

    public record ReloadResult(boolean successful, String error) {
        static ReloadResult success() {
            return new ReloadResult(true, "");
        }

        static ReloadResult failure(String error) {
            return new ReloadResult(false, error);
        }
    }
}
