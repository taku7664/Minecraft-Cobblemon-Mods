package jbro.cobblemon.bettermusic.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;

public final class MusicMappingOverridesParser {
    private MusicMappingOverridesParser() {
    }

    public static MusicMappingOverrides parse(Reader reader) {
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
        CatalogJson.only(root, "$", "schemaVersion", "field", "battle");
        int schema = CatalogJson.integer(root, "schemaVersion", "$");
        if (schema != 1) {
            throw CatalogJson.error("$.schemaVersion", "must be 1");
        }
        return new MusicMappingOverrides(
            root.has("field") ? field(CatalogJson.object(root, "field", "$")) : MusicMappingOverrides.Field.empty(),
            root.has("battle") ? battle(CatalogJson.object(root, "battle", "$")) : MusicMappingOverrides.Battle.empty()
        );
    }

    private static MusicMappingOverrides.Field field(JsonObject object) {
        String path = "$.field";
        CatalogJson.only(object, path, "default", "dimensions", "biomes", "biomePathContains", "underground");
        return new MusicMappingOverrides.Field(
            MusicCatalogParser.optionalId(object, "default", path),
            MusicCatalogParser.idMap(
                CatalogJson.optionalObject(object, "dimensions", path), path + ".dimensions", MusicCatalogParser.KeyType.RESOURCE
            ),
            MusicCatalogParser.idMap(
                CatalogJson.optionalObject(object, "biomes", path), path + ".biomes", MusicCatalogParser.KeyType.BIOME
            ),
            MusicCatalogParser.idMap(
                CatalogJson.optionalObject(object, "biomePathContains", path), path + ".biomePathContains", MusicCatalogParser.KeyType.PATH
            ),
            MusicCatalogParser.optionalId(object, "underground", path)
        );
    }

    private static MusicMappingOverrides.Battle battle(JsonObject object) {
        String path = "$.battle";
        CatalogJson.only(object, path, "wild", "trainer", "pvp", "content", "legendary", "ultraBeast", "pokemon");
        Map<String, String> content = MusicCatalogParser.idMap(
            CatalogJson.optionalObject(object, "content", path), path + ".content", MusicCatalogParser.KeyType.RESOURCE
        );
        return new MusicMappingOverrides.Battle(
            MusicCatalogParser.optionalId(object, "wild", path),
            MusicCatalogParser.optionalId(object, "trainer", path),
            MusicCatalogParser.optionalId(object, "pvp", path),
            content,
            MusicCatalogParser.optionalId(object, "legendary", path),
            MusicCatalogParser.optionalId(object, "ultraBeast", path),
            MusicCatalogParser.pokemon(CatalogJson.optionalArray(object, "pokemon", path), path + ".pokemon")
        );
    }
}
