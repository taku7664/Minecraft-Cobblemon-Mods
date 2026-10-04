package jbro.cobblemon.popupemotes.client.custom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.util.List;

public final class CustomEmoteConfigCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int VERSION = 1;

    private CustomEmoteConfigCodec() {
    }

    public static String encode(List<CustomEmoteDefinition> entries) {
        return GSON.toJson(new Config(VERSION, List.copyOf(entries)));
    }

    public static List<CustomEmoteDefinition> decode(String json) {
        Config config;
        try {
            config = GSON.fromJson(json, Config.class);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid custom emote config", exception);
        }
        if (config == null || config.version != VERSION || config.customEmotes == null) {
            throw new IllegalArgumentException("Unsupported custom emote config");
        }
        return new CustomEmoteCatalog(config.customEmotes).entries();
    }

    private record Config(int version, List<CustomEmoteDefinition> customEmotes) {
    }
}
