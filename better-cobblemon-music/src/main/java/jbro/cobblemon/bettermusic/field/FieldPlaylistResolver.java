package jbro.cobblemon.bettermusic.field;

import java.util.Map;
import java.util.Objects;
import jbro.cobblemon.bettermusic.config.FieldMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class FieldPlaylistResolver {
    private final FieldMusicConfig config;

    public FieldPlaylistResolver(FieldMusicConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Selection select(FieldMusicContext context) {
        Objects.requireNonNull(context, "context");

        PlaylistDefinition dimension = config.dimensions().get(context.dimensionId());
        if (dimension != null) {
            return new Selection("field.dimension:" + context.dimensionId(), dimension);
        }

        PlaylistDefinition exactBiome = config.biomes().get(context.biomeId());
        if (exactBiome != null) {
            return new Selection("field.biome:" + context.biomeId(), exactBiome);
        }

        if (context.underground() && config.underground().isPresent()) {
            return new Selection("field.underground", config.underground().orElseThrow());
        }

        for (Map.Entry<String, PlaylistDefinition> entry : config.biomes().entrySet()) {
            String selector = entry.getKey();
            if (selector.startsWith("#") && context.biomeTags().contains(selector.substring(1))) {
                return new Selection("field.biome:" + selector, entry.getValue());
            }
        }

        for (Map.Entry<String, PlaylistDefinition> entry : config.biomePathContains().entrySet()) {
            if (context.biomePath().contains(entry.getKey())) {
                return new Selection("field.path:" + entry.getKey(), entry.getValue());
            }
        }

        return new Selection("field.default", config.defaultPlaylist());
    }

    public record Selection(String id, PlaylistDefinition playlist) {
        public Selection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playlist, "playlist");
        }
    }
}
