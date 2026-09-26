package jbro.cobblemon.bettermusic.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import jbro.cobblemon.bettermusic.config.AudioEffectsSettings;
import jbro.cobblemon.bettermusic.config.GlobalMusicSettings;
import jbro.cobblemon.bettermusic.config.GlobalMusicSettingsStore;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class MusicCatalogConfigStore {
    public static final String SETTINGS_FILE_NAME = "settings.json";
    public static final String OVERRIDES_FILE_NAME = "overrides.json";
    public static final String LEGACY_FILE_NAME = "music.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configDirectory;
    private final Path settingsFile;
    private final Path overridesFile;
    private final Path legacyFile;

    public MusicCatalogConfigStore(Path configDirectory) {
        this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory")
            .toAbsolutePath().normalize();
        this.settingsFile = this.configDirectory.resolve(SETTINGS_FILE_NAME);
        this.overridesFile = this.configDirectory.resolve(OVERRIDES_FILE_NAME);
        this.legacyFile = this.configDirectory.resolve(LEGACY_FILE_NAME);
    }

    public MusicCatalogSettings initializeSettings(String defaultBasePackId) throws IOException {
        Objects.requireNonNull(defaultBasePackId, "defaultBasePackId");
        Files.createDirectories(configDirectory);
        if (Files.isRegularFile(settingsFile)) {
            return loadSettings();
        }
        MusicCatalogSettings candidate = Files.isRegularFile(legacyFile)
            ? fromLegacy(defaultBasePackId, GlobalMusicSettingsStore.load(legacyFile))
            : MusicCatalogSettings.defaults(defaultBasePackId);
        try {
            writeNew(settingsFile, settingsJson(candidate));
            return candidate;
        } catch (FileAlreadyExistsException ignored) {
            return loadSettings();
        }
    }

    public MusicCatalogSettings loadSettings() throws IOException {
        return parseSettings(Files.readString(settingsFile, StandardCharsets.UTF_8));
    }

    public void saveSettings(MusicCatalogSettings settings) throws IOException {
        writeReplace(settingsFile, settingsJson(Objects.requireNonNull(settings, "settings")));
    }

    public MusicMappingOverrides loadOverrides() throws IOException {
        if (!Files.isRegularFile(overridesFile)) {
            return MusicMappingOverrides.empty();
        }
        return MusicMappingOverridesParser.parse(Files.newBufferedReader(overridesFile, StandardCharsets.UTF_8));
    }

    public boolean saveOverridesIfMissing(MusicMappingOverrides overrides) throws IOException {
        Objects.requireNonNull(overrides, "overrides");
        if (Files.exists(overridesFile)) {
            return false;
        }
        try {
            writeNew(overridesFile, overridesJson(overrides));
            return true;
        } catch (FileAlreadyExistsException ignored) {
            return false;
        }
    }

    public void saveOverrides(MusicMappingOverrides overrides) throws IOException {
        writeReplace(overridesFile, overridesJson(Objects.requireNonNull(overrides, "overrides")));
    }

    public Path settingsFile() {
        return settingsFile;
    }

    public Path overridesFile() {
        return overridesFile;
    }

    public Path legacyFile() {
        return legacyFile;
    }

    private static MusicCatalogSettings fromLegacy(String basePackId, GlobalMusicSettings legacy) {
        return new MusicCatalogSettings(
            basePackId,
            new PlaybackSettings(
                legacy.scanIntervalSeconds(),
                legacy.fieldChangeDelaySeconds(),
                legacy.betweenTracksSeconds(),
                legacy.fadeInSeconds(),
                legacy.fadeOutSeconds()
            ),
            legacy.selection(),
            legacy.volume(),
            legacy.audioEffects()
        );
    }

    private static MusicCatalogSettings parseSettings(String json) {
        JsonObject root;
        try {
            var element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw CatalogJson.error("$", "must be an object");
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException exception) {
            if (exception instanceof CatalogValidationException validation) {
                throw validation;
            }
            throw CatalogJson.error("$", "invalid JSON: " + exception.getMessage());
        }
        CatalogJson.only(
            root,
            "$",
            "schemaVersion", "basePackId", "scanIntervalSeconds", "fieldChangeDelaySeconds",
            "betweenTracksSeconds", "fadeInSeconds", "fadeOutSeconds", "selection", "volume",
            "hitSoundsEnabled", "hitSoundVolume", "lastPokemonHpEffectsEnabled", "lastPokemonHpEffectVolume"
        );
        if (CatalogJson.integer(root, "schemaVersion", "$") != 1) {
            throw CatalogJson.error("$.schemaVersion", "must be 1");
        }
        String basePackId = CatalogJson.resourceId(CatalogJson.string(root, "basePackId", "$"), "$.basePackId");
        PlaybackSettings playback = new PlaybackSettings(
            CatalogJson.number(root, "scanIntervalSeconds", "$"),
            CatalogJson.number(root, "fieldChangeDelaySeconds", "$"),
            CatalogJson.number(root, "betweenTracksSeconds", "$"),
            CatalogJson.number(root, "fadeInSeconds", "$"),
            CatalogJson.number(root, "fadeOutSeconds", "$")
        );
        if (playback.scanIntervalSeconds() < 0.25) {
            throw CatalogJson.error("$.scanIntervalSeconds", "must be at least 0.25 seconds");
        }
        PlaylistDefinition.Selection selection = CatalogJson.enumValue(
            CatalogJson.string(root, "selection", "$"),
            PlaylistDefinition.Selection.class,
            "$.selection"
        );
        double volume = CatalogJson.number(root, "volume", "$");
        AudioEffectsSettings effects = new AudioEffectsSettings(
            bool(root, "hitSoundsEnabled"),
            CatalogJson.number(root, "hitSoundVolume", "$"),
            bool(root, "lastPokemonHpEffectsEnabled"),
            CatalogJson.number(root, "lastPokemonHpEffectVolume", "$")
        );
        return new MusicCatalogSettings(basePackId, playback, selection, volume, effects);
    }

    private static boolean bool(JsonObject root, String key) {
        if (!root.has(key)
            || !root.get(key).isJsonPrimitive()
            || !root.get(key).getAsJsonPrimitive().isBoolean()) {
            throw CatalogJson.error("$." + key, "must be a boolean");
        }
        return root.get(key).getAsBoolean();
    }

    private static String settingsJson(MusicCatalogSettings settings) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("basePackId", settings.basePackId());
        root.addProperty("scanIntervalSeconds", settings.playback().scanIntervalSeconds());
        root.addProperty("fieldChangeDelaySeconds", settings.playback().fieldChangeDelaySeconds());
        root.addProperty("betweenTracksSeconds", settings.playback().betweenTracksSeconds());
        root.addProperty("fadeInSeconds", settings.playback().fadeInSeconds());
        root.addProperty("fadeOutSeconds", settings.playback().fadeOutSeconds());
        root.addProperty("selection", settings.selection().name().toLowerCase(Locale.ROOT));
        root.addProperty("volume", settings.volume());
        root.addProperty("hitSoundsEnabled", settings.audioEffects().hitSoundsEnabled());
        root.addProperty("hitSoundVolume", settings.audioEffects().hitSoundVolume());
        root.addProperty("lastPokemonHpEffectsEnabled", settings.audioEffects().lastPokemonHpEffectsEnabled());
        root.addProperty("lastPokemonHpEffectVolume", settings.audioEffects().lastPokemonHpEffectVolume());
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static String overridesJson(MusicMappingOverrides overrides) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonObject field = new JsonObject();
        overrides.field().defaultPlaylistId().ifPresent(value -> field.addProperty("default", value));
        addMap(field, "dimensions", overrides.field().dimensions());
        addMap(field, "biomes", overrides.field().biomes());
        addMap(field, "biomePathContains", overrides.field().biomePathContains());
        overrides.field().undergroundPlaylistId().ifPresent(value -> field.addProperty("underground", value));
        if (!field.isEmpty()) {
            root.add("field", field);
        }
        JsonObject battle = new JsonObject();
        overrides.battle().wildPlaylistId().ifPresent(value -> battle.addProperty("wild", value));
        overrides.battle().trainerPlaylistId().ifPresent(value -> battle.addProperty("trainer", value));
        overrides.battle().pvpPlaylistId().ifPresent(value -> battle.addProperty("pvp", value));
        addMap(battle, "content", overrides.battle().content());
        overrides.battle().legendaryPlaylistId().ifPresent(value -> battle.addProperty("legendary", value));
        overrides.battle().ultraBeastPlaylistId().ifPresent(value -> battle.addProperty("ultraBeast", value));
        if (!overrides.battle().pokemon().isEmpty()) {
            var pokemon = new com.google.gson.JsonArray();
            for (CatalogMappings.PokemonMapping rule : overrides.battle().pokemon()) {
                JsonObject item = new JsonObject();
                var species = new com.google.gson.JsonArray();
                rule.species().stream().sorted().forEach(species::add);
                item.add("species", species);
                if (!rule.only().isEmpty()) {
                    var only = new com.google.gson.JsonArray();
                    rule.only().stream()
                        .map(value -> value.name().toLowerCase(Locale.ROOT))
                        .sorted()
                        .forEach(only::add);
                    item.add("only", only);
                }
                item.addProperty("playlist", rule.playlistId());
                pokemon.add(item);
            }
            battle.add("pokemon", pokemon);
        }
        if (!battle.isEmpty()) {
            root.add("battle", battle);
        }
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static void addMap(JsonObject parent, String key, Map<String, String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        parent.add(key, object);
    }

    private static void writeNew(Path target, String content) throws IOException {
        write(target, content, false);
    }

    private static void writeReplace(Path target, String content) throws IOException {
        write(target, content, true);
    }

    private static void write(Path target, String content, boolean replace) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException ignored) {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
