package jbro.cobblemon.clientsetup;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Rounding-Block ships with rendering enabled; turn it off once so players opt in themselves. */
public final class RoundingBlockSetup {
    private static final String MOD_ID = "rounding_block";
    private static final String RULE = "rounding-block-disabled-v1";

    private RoundingBlockSetup() {}

    public static synchronized ClientSetup.Result apply(Path configDirectory, Set<String> installedMods) throws IOException {
        if (!installedMods.contains(MOD_ID)) return ClientSetup.Result.MOD_ABSENT;
        Properties state = SetupFiles.readState(configDirectory);
        if ("true".equals(state.getProperty(RULE))) return ClientSetup.Result.ALREADY_APPLIED;
        Path file = configDirectory.resolve("rounding-block.json");
        JsonObject config = read(file);
        JsonElement enabled = config.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean() || enabled.getAsBoolean()) {
            config.addProperty("enabled", false);
            SetupFiles.atomicWrite(file, new GsonBuilder().setPrettyPrinting().create().toJson(config));
        }
        SetupFiles.markApplied(configDirectory, state, List.of(RULE));
        return ClientSetup.Result.APPLIED;
    }

    private static JsonObject read(Path file) throws IOException {
        if (!Files.exists(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) throw new IOException("Rounding-Block config root must be a JSON object: " + file);
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            // Leave a malformed file for the user instead of replacing their other settings.
            throw new IOException("Could not read Rounding-Block config: " + file, exception);
        }
    }
}
