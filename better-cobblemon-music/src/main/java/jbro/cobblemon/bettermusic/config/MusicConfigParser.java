package jbro.cobblemon.bettermusic.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class MusicConfigParser {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern BARE_SPECIES = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH_TOKEN = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern MUSIC_RESOURCE_PATH = Pattern.compile("[a-z0-9_./-]+\\.ogg");
    private static final Set<String> SUPPORTED_TRAINER_ROLES = Set.of("champion", "elite", "gym", "rival");
    private static final double MIN_SCAN_INTERVAL_SECONDS = 0.25;

    private MusicConfigParser() {
    }

    public static MusicConfig parse(Reader reader) {
        JsonObject root = root(reader);
        rejectUnknown(root, "$", Set.of(
            "schemaVersion",
            "scanIntervalSeconds",
            "fieldChangeDelaySeconds",
            "betweenTracksSeconds",
            "fadeInSeconds",
            "fadeOutSeconds",
            "selection",
            "volume",
            "field",
            "battle"
        ));
        int schemaVersion = schemaVersion(root);

        double scanInterval = positiveNumber(root, "scanIntervalSeconds", "$.scanIntervalSeconds");
        if (scanInterval < MIN_SCAN_INTERVAL_SECONDS) {
            throw error("$.scanIntervalSeconds", "must be at least 0.25 seconds");
        }
        double fieldDelay = nonNegativeNumber(root, "fieldChangeDelaySeconds", "$.fieldChangeDelaySeconds");
        double betweenTracks = nonNegativeNumber(root, "betweenTracksSeconds", "$.betweenTracksSeconds");
        double fadeIn = nonNegativeNumber(root, "fadeInSeconds", "$.fadeInSeconds");
        double fadeOut = nonNegativeNumber(root, "fadeOutSeconds", "$.fadeOutSeconds");
        var defaultSelection = selection(root, "selection", "$.selection");
        double defaultVolume = nonNegativeNumber(root, "volume", "$.volume");
        var defaults = new PlaylistDefaults(defaultSelection, defaultVolume, betweenTracks);

        var playback = new PlaybackSettings(
            scanInterval,
            fieldDelay,
            betweenTracks,
            fadeIn,
            fadeOut,
            PlaybackSettings.MissingCueBehavior.KEEP_ORIGINAL
        );
        return new MusicConfig(
            playback,
            parseField(object(root, "field", "$.field"), defaults, schemaVersion),
            parseBattle(object(root, "battle", "$.battle"), defaults)
        );
    }

    private static FieldMusicConfig parseField(
        JsonObject object,
        PlaylistDefaults defaults,
        int schemaVersion
    ) {
        Set<String> allowed = schemaVersion == 1
            ? Set.of("default", "dimensions", "biomes", "biomePathContains", "underground")
            : Set.of(
                "default", "dimensions", "biomes", "biomeTags", "biomePathContains", "underground"
            );
        rejectUnknown(object, "$.field", allowed);
        var defaultPlaylist = playlist(required(object, "default", "$.field.default"), "$.field.default", defaults);
        var dimensions = playlistMap(
            object(object, "dimensions", "$.field.dimensions"),
            "$.field.dimensions",
            defaults,
            KeyKind.RESOURCE
        );
        var biomes = playlistMap(
            object(object, "biomes", "$.field.biomes"),
            "$.field.biomes",
            defaults,
            schemaVersion == 1 ? KeyKind.BIOME_SELECTOR : KeyKind.RESOURCE
        );
        if (schemaVersion == 2) {
            Map<String, PlaylistDefinition> combinedBiomes = new LinkedHashMap<>(biomes);
            combinedBiomes.putAll(orderedPlaylistRules(
                array(object, "biomeTags", "$.field.biomeTags"),
                "$.field.biomeTags",
                "tag",
                KeyKind.RESOURCE,
                "#",
                defaults
            ));
            biomes = combinedBiomes;
        }
        var pathContains = schemaVersion == 1
            ? playlistMap(
                object(object, "biomePathContains", "$.field.biomePathContains"),
                "$.field.biomePathContains",
                defaults,
                KeyKind.PATH_TOKEN
            )
            : orderedPlaylistRules(
                array(object, "biomePathContains", "$.field.biomePathContains"),
                "$.field.biomePathContains",
                "contains",
                KeyKind.PATH_TOKEN,
                "",
                defaults
            );
        Optional<PlaylistDefinition> underground = object.has("underground")
            ? Optional.of(playlist(object.get("underground"), "$.field.underground", defaults))
            : Optional.empty();
        return new FieldMusicConfig(defaultPlaylist, dimensions, biomes, pathContains, underground);
    }

    private static Map<String, PlaylistDefinition> orderedPlaylistRules(
        JsonArray rules,
        String path,
        String selectorKey,
        KeyKind keyKind,
        String storedPrefix,
        PlaylistDefaults defaults
    ) {
        Map<String, PlaylistDefinition> result = new LinkedHashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            String rulePath = path + "[" + index + "]";
            JsonObject rule = asObject(rules.get(index), rulePath);
            rejectUnknown(rule, rulePath, Set.of(selectorKey, "playlist"));
            String selectorPath = rulePath + "." + selectorKey;
            String selector = string(rule, selectorKey, selectorPath);
            validateKey(selector, selectorPath, keyKind);
            String storedSelector = storedPrefix + selector;
            if (result.containsKey(storedSelector)) {
                throw error(selectorPath, "duplicate ordered selector '" + selector + "'");
            }
            result.put(
                storedSelector,
                playlist(required(rule, "playlist", rulePath + ".playlist"), rulePath + ".playlist", defaults)
            );
        }
        return result;
    }

    private static BattleMusicConfig parseBattle(JsonObject object, PlaylistDefaults defaults) {
        rejectUnknown(object, "$.battle", Set.of(
            "wild", "trainer", "pvp", "content", "gym", "roles",
            "legendary", "ultraBeast", "pokemon"
        ));
        var wild = playlist(required(object, "wild", "$.battle.wild"), "$.battle.wild", defaults);
        var trainer = playlist(required(object, "trainer", "$.battle.trainer"), "$.battle.trainer", defaults);
        var pvp = playlist(required(object, "pvp", "$.battle.pvp"), "$.battle.pvp", defaults);
        var content = object.has("content")
            ? playlistMap(
                asObject(object.get("content"), "$.battle.content"),
                "$.battle.content",
                defaults,
                KeyKind.RESOURCE
            )
            : Map.<String, PlaylistDefinition>of();
        var roles = parseRoles(object, defaults);
        var pokemon = parsePokemon(array(object, "pokemon", "$.battle.pokemon"), defaults);
        return new BattleMusicConfig(
            wild,
            trainer,
            pvp,
            content,
            roles,
            optionalPlaylist(object, "legendary", "$.battle.legendary", defaults),
            optionalPlaylist(object, "ultraBeast", "$.battle.ultraBeast", defaults),
            pokemon
        );
    }

    private static Map<String, PlaylistDefinition> parseRoles(JsonObject battle, PlaylistDefaults defaults) {
        Map<String, PlaylistDefinition> roles = battle.has("roles")
            ? new LinkedHashMap<>(playlistMap(
                asObject(battle.get("roles"), "$.battle.roles"),
                "$.battle.roles",
                defaults,
                KeyKind.ROLE_ID
            ))
            : new LinkedHashMap<>();
        for (String role : roles.keySet()) {
            if (!SUPPORTED_TRAINER_ROLES.contains(role)) {
                throw error(
                    "$.battle.roles['" + role + "']",
                    "must be champion, elite, gym, or rival"
                );
            }
        }
        if (battle.has("gym")) {
            if (roles.containsKey("gym")) {
                throw error("$.battle.roles.gym", "duplicates the legacy $.battle.gym playlist");
            }
            roles.put("gym", playlist(battle.get("gym"), "$.battle.gym", defaults));
        }
        return Map.copyOf(roles);
    }

    private static List<BattleMusicConfig.PokemonRule> parsePokemon(
        JsonArray array,
        PlaylistDefaults defaults
    ) {
        List<BattleMusicConfig.PokemonRule> rules = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "$.battle.pokemon[" + index + "]";
            JsonObject rule = asObject(array.get(index), path);
            rejectUnknown(rule, path, Set.of(
                "species", "only", "selection", "volume", "betweenTracksSeconds", "tracks"
            ));
            Set<String> species = speciesSet(array(rule, "species", path + ".species"), path + ".species");
            Set<BattleMusicConfig.BattleType> only = rule.has("only")
                ? battleTypes(array(rule, "only", path + ".only"), path + ".only")
                : Set.of();
            PlaylistDefinition playlist = playlistObject(rule, path, defaults);
            rules.add(new BattleMusicConfig.PokemonRule(species, only, playlist));
        }
        return List.copyOf(rules);
    }

    private static Optional<PlaylistDefinition> optionalPlaylist(
        JsonObject object,
        String key,
        String path,
        PlaylistDefaults defaults
    ) {
        return object.has(key) ? Optional.of(playlist(object.get(key), path, defaults)) : Optional.empty();
    }

    private static Map<String, PlaylistDefinition> playlistMap(
        JsonObject object,
        String path,
        PlaylistDefaults defaults,
        KeyKind keyKind
    ) {
        Map<String, PlaylistDefinition> result = new LinkedHashMap<>();
        for (var entry : object.entrySet()) {
            String keyPath = path + "['" + entry.getKey() + "']";
            validateKey(entry.getKey(), keyPath, keyKind);
            result.put(entry.getKey(), playlist(entry.getValue(), keyPath, defaults));
        }
        return result;
    }

    private static PlaylistDefinition playlist(
        JsonElement element,
        String path,
        PlaylistDefaults defaults
    ) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new PlaylistDefinition(
                defaults.selection,
                defaults.volume,
                defaults.betweenTracksSeconds,
                List.of(trackPath(element.getAsString(), path))
            );
        }
        if (element.isJsonArray()) {
            return new PlaylistDefinition(
                defaults.selection,
                defaults.volume,
                defaults.betweenTracksSeconds,
                trackList(element.getAsJsonArray(), path)
            );
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            rejectUnknown(object, path, Set.of("selection", "volume", "betweenTracksSeconds", "tracks"));
            return playlistObject(object, path, defaults);
        }
        throw error(path, "must be an .ogg path, an array of paths, or a playlist object");
    }

    private static PlaylistDefinition playlistObject(
        JsonObject object,
        String path,
        PlaylistDefaults defaults
    ) {
        var selected = object.has("selection")
            ? selection(object, "selection", path + ".selection")
            : defaults.selection;
        double volume = object.has("volume")
            ? nonNegativeNumber(object, "volume", path + ".volume")
            : defaults.volume;
        double betweenTracks = object.has("betweenTracksSeconds")
            ? nonNegativeNumber(object, "betweenTracksSeconds", path + ".betweenTracksSeconds")
            : defaults.betweenTracksSeconds;
        String tracksPath = path + ".tracks";
        JsonArray tracks = array(object, "tracks", tracksPath);
        return new PlaylistDefinition(selected, volume, betweenTracks, trackList(tracks, tracksPath));
    }

    private static List<String> trackList(JsonArray array, String path) {
        if (array.isEmpty()) {
            throw error(path, "must contain at least one track");
        }
        List<String> tracks = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String itemPath = path + "[" + index + "]";
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw error(itemPath, "must be a relative .ogg path");
            }
            String track = trackPath(element.getAsString(), itemPath);
            if (!unique.add(track)) {
                throw error(itemPath, "duplicate track '" + track + "'");
            }
            tracks.add(track);
        }
        return List.copyOf(tracks);
    }

    private static String trackPath(String value, String path) {
        if (value.isBlank()
            || value.startsWith("/")
            || value.contains("\\")
            || value.contains(":")
            || !value.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            throw error(path, "must be a relative .ogg path below the music directory");
        }
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw error(path, "must be a relative .ogg path below the music directory");
            }
        }
        if (!MUSIC_RESOURCE_PATH.matcher(value).matches()) {
            throw error(path, "must be a lowercase Minecraft resource path ending in .ogg");
        }
        return value;
    }

    private static Set<String> speciesSet(JsonArray array, String path) {
        if (array.isEmpty()) {
            throw error(path, "must contain at least one species");
        }
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            String value = stringElement(array.get(index), itemPath);
            String normalized;
            if (BARE_SPECIES.matcher(value).matches()) {
                normalized = "cobblemon:" + value;
            } else if (RESOURCE_ID.matcher(value).matches()) {
                normalized = value;
            } else {
                throw error(itemPath, "must be a Cobblemon species name or namespaced species id");
            }
            if (!result.add(normalized)) {
                throw error(itemPath, "duplicate species '" + normalized + "'");
            }
        }
        return Set.copyOf(result);
    }

    private static Set<BattleMusicConfig.BattleType> battleTypes(JsonArray array, String path) {
        if (array.isEmpty()) {
            throw error(path, "must not be empty when present");
        }
        Set<BattleMusicConfig.BattleType> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            String value = stringElement(array.get(index), itemPath);
            try {
                result.add(BattleMusicConfig.BattleType.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw error(itemPath, "must be WILD, TRAINER, or PVP");
            }
        }
        return Set.copyOf(result);
    }

    private static PlaylistDefinition.Selection selection(JsonObject object, String key, String path) {
        String value = string(object, key, path);
        try {
            return PlaylistDefinition.Selection.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw error(path, "must be SHUFFLE, RANDOM, or SEQUENTIAL");
        }
    }

    private static void validateKey(String key, String path, KeyKind kind) {
        boolean valid = switch (kind) {
            case RESOURCE -> RESOURCE_ID.matcher(key).matches();
            case BIOME_SELECTOR -> key.startsWith("#")
                ? RESOURCE_ID.matcher(key.substring(1)).matches()
                : RESOURCE_ID.matcher(key).matches();
            case PATH_TOKEN -> PATH_TOKEN.matcher(key).matches();
            case ROLE_ID -> BARE_SPECIES.matcher(key).matches();
        };
        if (!valid) {
            throw error(path, "invalid " + kind.description);
        }
    }

    private static JsonObject root(Reader reader) {
        try {
            JsonElement element = JsonParser.parseReader(reader);
            return asObject(element, "$");
        } catch (JsonParseException exception) {
            throw error("$", "invalid JSON: " + exception.getMessage());
        }
    }

    private static JsonObject asObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw error(path, "must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonObject object(JsonObject parent, String key, String path) {
        return asObject(required(parent, key, path), path);
    }

    private static JsonArray array(JsonObject parent, String key, String path) {
        JsonElement element = required(parent, key, path);
        if (!element.isJsonArray()) {
            throw error(path, "must be an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonElement required(JsonObject object, String key, String path) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw error(path, "is required");
        }
        return object.get(key);
    }

    private static String string(JsonObject object, String key, String path) {
        return stringElement(required(object, key, path), path);
    }

    private static String stringElement(JsonElement element, String path) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw error(path, "must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw error(path, "must not be blank");
        }
        return value;
    }

    private static double positiveNumber(JsonObject object, String key, String path) {
        double value = number(object, key, path);
        if (value <= 0.0) {
            throw error(path, "must be greater than zero");
        }
        return value;
    }

    private static double nonNegativeNumber(JsonObject object, String key, String path) {
        double value = number(object, key, path);
        if (value < 0.0) {
            throw error(path, "must be non-negative");
        }
        return value;
    }

    private static double number(JsonObject object, String key, String path) {
        JsonElement element = required(object, key, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw error(path, "must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw error(path, "must be finite");
        }
        return value;
    }

    private static int schemaVersion(JsonObject root) {
        double version = number(root, "schemaVersion", "$.schemaVersion");
        if (version != Math.rint(version) || (version != 1.0 && version != 2.0)) {
            throw error("$.schemaVersion", "must be integer 1 or 2");
        }
        return (int) version;
    }

    private static void rejectUnknown(JsonObject object, String path, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw error(path + "." + key, "unknown property");
            }
        }
    }

    private static ConfigValidationException error(String path, String message) {
        return new ConfigValidationException(path, message);
    }

    private record PlaylistDefaults(
        PlaylistDefinition.Selection selection,
        double volume,
        double betweenTracksSeconds
    ) {
    }

    private enum KeyKind {
        RESOURCE("resource id"),
        BIOME_SELECTOR("biome id or #tag"),
        PATH_TOKEN("biome path fragment"),
        ROLE_ID("trainer role id");

        private final String description;

        KeyKind(String description) {
            this.description = description;
        }
    }
}
