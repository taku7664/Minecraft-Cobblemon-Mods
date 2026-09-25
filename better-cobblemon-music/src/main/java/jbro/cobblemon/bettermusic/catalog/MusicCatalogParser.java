package jbro.cobblemon.bettermusic.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class MusicCatalogParser {
    private MusicCatalogParser() {
    }

    public static MusicCatalog parse(Reader reader) {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw CatalogJson.error("$", "must be an object");
            }
            root = element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw CatalogJson.error("$", "invalid JSON: " + exception.getMessage());
        }
        CatalogJson.only(root, "$", "schemaVersion", "packId", "kind", "tracks", "playlists", "mappings", "audioEvents");
        int schemaVersion = CatalogJson.integer(root, "schemaVersion", "$");
        if (schemaVersion != 1) {
            throw CatalogJson.error("$.schemaVersion", "must be 1");
        }
        String packId = CatalogJson.resourceId(CatalogJson.string(root, "packId", "$"), "$.packId");
        MusicCatalog.Kind kind = CatalogJson.enumValue(
            CatalogJson.string(root, "kind", "$"), MusicCatalog.Kind.class, "$.kind"
        );
        Map<String, MusicCatalog.Track> tracks = tracks(CatalogJson.object(root, "tracks", "$"));
        Map<String, MusicCatalog.Playlist> playlists = playlists(CatalogJson.object(root, "playlists", "$"));
        if (tracks.isEmpty()) {
            throw CatalogJson.error("$.tracks", "must contain at least one track");
        }
        if (playlists.isEmpty()) {
            throw CatalogJson.error("$.playlists", "must contain at least one playlist");
        }

        Optional<CatalogMappings> mappings = root.has("mappings")
            ? Optional.of(mappings(CatalogJson.object(root, "mappings", "$")))
            : Optional.empty();
        Optional<MusicCatalog.AudioEvents> audioEvents = root.has("audioEvents")
            ? Optional.of(audioEvents(CatalogJson.object(root, "audioEvents", "$")))
            : Optional.empty();
        if (kind == MusicCatalog.Kind.BASE) {
            if (mappings.isEmpty()) {
                throw CatalogJson.error("$.mappings", "is required for a base catalog");
            }
            if (audioEvents.isEmpty()) {
                throw CatalogJson.error("$.audioEvents", "is required for a base catalog");
            }
        } else {
            if (mappings.isPresent()) {
                throw CatalogJson.error("$.mappings", "extension catalogs cannot declare default mappings");
            }
            if (audioEvents.isPresent()) {
                throw CatalogJson.error("$.audioEvents", "extension catalogs cannot replace base audio events");
            }
        }
        return new MusicCatalog(schemaVersion, packId, kind, tracks, playlists, mappings, audioEvents);
    }

    private static Map<String, MusicCatalog.Track> tracks(JsonObject object) {
        Map<String, MusicCatalog.Track> tracks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String path = "$.tracks." + entry.getKey();
            String id = CatalogJson.resourceId(entry.getKey(), path);
            if (!entry.getValue().isJsonObject()) {
                throw CatalogJson.error(path, "must be an object");
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            CatalogJson.only(value, path, "event", "title");
            String event = CatalogJson.resourceId(CatalogJson.string(value, "event", path), path + ".event");
            String title = CatalogJson.string(value, "title", path);
            tracks.put(id, new MusicCatalog.Track(event, title));
        }
        return tracks;
    }

    private static Map<String, MusicCatalog.Playlist> playlists(JsonObject object) {
        Map<String, MusicCatalog.Playlist> playlists = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String path = "$.playlists." + entry.getKey();
            String id = CatalogJson.resourceId(entry.getKey(), path);
            if (!entry.getValue().isJsonObject()) {
                throw CatalogJson.error(path, "must be an object");
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            CatalogJson.only(value, path, "selection", "volume", "betweenTracksSeconds", "tracks");
            Optional<PlaylistDefinition.Selection> selection = value.has("selection")
                ? Optional.of(CatalogJson.enumValue(
                    CatalogJson.string(value, "selection", path),
                    PlaylistDefinition.Selection.class,
                    path + ".selection"
                ))
                : Optional.empty();
            Optional<Double> volume = value.has("volume")
                ? Optional.of(nonNegative(CatalogJson.number(value, "volume", path), path + ".volume"))
                : Optional.empty();
            Optional<Double> between = value.has("betweenTracksSeconds")
                ? Optional.of(nonNegative(
                    CatalogJson.number(value, "betweenTracksSeconds", path),
                    path + ".betweenTracksSeconds"
                ))
                : Optional.empty();
            List<String> tracks = List.copyOf(CatalogJson.uniqueStrings(
                CatalogJson.array(value, "tracks", path),
                path + ".tracks",
                CatalogJson::resourceId
            ));
            if (tracks.isEmpty()) {
                throw CatalogJson.error(path + ".tracks", "must contain at least one track");
            }
            playlists.put(id, new MusicCatalog.Playlist(selection, volume, between, tracks));
        }
        return playlists;
    }

    private static CatalogMappings mappings(JsonObject object) {
        CatalogJson.only(object, "$.mappings", "field", "battle");
        return new CatalogMappings(
            fieldMappings(CatalogJson.object(object, "field", "$.mappings")),
            battleMappings(CatalogJson.object(object, "battle", "$.mappings"), "$.mappings.battle")
        );
    }

    private static CatalogMappings.Field fieldMappings(JsonObject object) {
        String path = "$.mappings.field";
        CatalogJson.only(object, path, "default", "dimensions", "biomes", "biomePathContains", "underground");
        return new CatalogMappings.Field(
            CatalogJson.resourceId(CatalogJson.string(object, "default", path), path + ".default"),
            idMap(CatalogJson.optionalObject(object, "dimensions", path), path + ".dimensions", KeyType.RESOURCE),
            idMap(CatalogJson.optionalObject(object, "biomes", path), path + ".biomes", KeyType.BIOME),
            idMap(CatalogJson.optionalObject(object, "biomePathContains", path), path + ".biomePathContains", KeyType.PATH),
            object.has("underground")
                ? Optional.of(CatalogJson.resourceId(CatalogJson.string(object, "underground", path), path + ".underground"))
                : Optional.empty()
        );
    }

    static CatalogMappings.Battle battleMappings(JsonObject object, String path) {
        CatalogJson.only(object, path, "wild", "trainer", "pvp", "content", "legendary", "ultraBeast", "pokemon");
        return new CatalogMappings.Battle(
            CatalogJson.resourceId(CatalogJson.string(object, "wild", path), path + ".wild"),
            CatalogJson.resourceId(CatalogJson.string(object, "trainer", path), path + ".trainer"),
            CatalogJson.resourceId(CatalogJson.string(object, "pvp", path), path + ".pvp"),
            idMap(CatalogJson.optionalObject(object, "content", path), path + ".content", KeyType.RESOURCE),
            optionalId(object, "legendary", path),
            optionalId(object, "ultraBeast", path),
            pokemon(CatalogJson.optionalArray(object, "pokemon", path), path + ".pokemon")
        );
    }

    static List<CatalogMappings.PokemonMapping> pokemon(com.google.gson.JsonArray array, String path) {
        List<CatalogMappings.PokemonMapping> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                throw CatalogJson.error(itemPath, "must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            CatalogJson.only(object, itemPath, "species", "only", "playlist");
            Set<String> species = CatalogJson.uniqueStrings(
                CatalogJson.array(object, "species", itemPath),
                itemPath + ".species",
                CatalogJson::species
            );
            if (species.isEmpty()) {
                throw CatalogJson.error(itemPath + ".species", "must contain at least one species");
            }
            Set<BattleMusicConfig.BattleType> only = new LinkedHashSet<>();
            for (String value : CatalogJson.uniqueStrings(
                CatalogJson.optionalArray(object, "only", itemPath),
                itemPath + ".only",
                (item, ignored) -> item
            )) {
                only.add(CatalogJson.enumValue(value, BattleMusicConfig.BattleType.class, itemPath + ".only"));
            }
            String playlist = CatalogJson.resourceId(
                CatalogJson.string(object, "playlist", itemPath), itemPath + ".playlist"
            );
            result.add(new CatalogMappings.PokemonMapping(species, only, playlist));
        }
        return List.copyOf(result);
    }

    private static MusicCatalog.AudioEvents audioEvents(JsonObject object) {
        String path = "$.audioEvents";
        CatalogJson.only(object, path, "hitNormal", "hitSuperEffective", "hitNotVeryEffective", "heartbeat");
        return new MusicCatalog.AudioEvents(
            event(object, "hitNormal", path),
            event(object, "hitSuperEffective", path),
            event(object, "hitNotVeryEffective", path),
            event(object, "heartbeat", path)
        );
    }

    static Map<String, String> idMap(JsonObject object, String path, KeyType keyType) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String keyPath = path + "." + entry.getKey();
            String key = switch (keyType) {
                case RESOURCE -> CatalogJson.resourceId(entry.getKey(), keyPath);
                case BIOME -> entry.getKey().startsWith("#")
                    ? "#" + CatalogJson.resourceId(entry.getKey().substring(1), keyPath)
                    : CatalogJson.resourceId(entry.getKey(), keyPath);
                case PATH -> CatalogJson.resourcePath(entry.getKey(), keyPath);
            };
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw CatalogJson.error(keyPath, "must be a playlist resource ID string");
            }
            result.put(key, CatalogJson.resourceId(entry.getValue().getAsString(), keyPath));
        }
        return result;
    }

    static Optional<String> optionalId(JsonObject object, String key, String path) {
        return object.has(key)
            ? Optional.of(CatalogJson.resourceId(CatalogJson.string(object, key, path), path + "." + key))
            : Optional.empty();
    }

    private static String event(JsonObject object, String key, String path) {
        return CatalogJson.resourceId(CatalogJson.string(object, key, path), path + "." + key);
    }

    private static double nonNegative(double value, String path) {
        if (value < 0.0 || value > Float.MAX_VALUE) {
            throw CatalogJson.error(path, "must be non-negative");
        }
        return value;
    }

    enum KeyType {
        RESOURCE,
        BIOME,
        PATH
    }
}
