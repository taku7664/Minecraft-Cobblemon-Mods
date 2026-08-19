package jbro.cobblemon.bettermusic.field;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.FieldMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import org.junit.jupiter.api.Test;

final class FieldPlaylistResolverTest {
    @Test
    void dimensionThenBiomeThenUndergroundThenPathThenDefaultDefinesPrecedence() {
        var resolver = new FieldPlaylistResolver(config());

        assertEquals("field.dimension:minecraft:the_nether", resolver.select(context(
            "minecraft:the_nether", "minecraft:deep_dark", Set.of("minecraft:is_forest"), true
        )).id());
        assertEquals("field.biome:minecraft:deep_dark", resolver.select(context(
            "minecraft:overworld", "minecraft:deep_dark", Set.of(), true
        )).id());
        assertEquals("field.underground", resolver.select(context(
            "minecraft:overworld", "minecraft:forest", Set.of("minecraft:is_forest"), true
        )).id());
        assertEquals("field.biome:#minecraft:is_forest", resolver.select(context(
            "minecraft:overworld", "minecraft:forest", Set.of("minecraft:is_forest"), false
        )).id());
        assertEquals("field.path:river", resolver.select(context(
            "minecraft:overworld", "minecraft:frozen_river", Set.of(), false
        )).id());
        assertEquals("field.default", resolver.select(context(
            "minecraft:overworld", "minecraft:plains", Set.of(), false
        )).id());
    }

    private static FieldMusicConfig config() {
        Map<String, PlaylistDefinition> dimensions = Map.of("minecraft:the_nether", playlist("nether.ogg"));
        Map<String, PlaylistDefinition> biomes = new LinkedHashMap<>();
        biomes.put("minecraft:deep_dark", playlist("deep_dark.ogg"));
        biomes.put("#minecraft:is_forest", playlist("forest.ogg"));
        return new FieldMusicConfig(
            playlist("plains.ogg"),
            dimensions,
            biomes,
            Map.of("river", playlist("river.ogg")),
            Optional.of(playlist("cave.ogg"))
        );
    }

    private static PlaylistDefinition playlist(String track) {
        return new PlaylistDefinition(PlaylistDefinition.Selection.SHUFFLE, 1.0, 0.0, List.of(track));
    }

    private static FieldMusicContext context(
        String dimension,
        String biome,
        Set<String> tags,
        boolean underground
    ) {
        return new FieldMusicContext(dimension, biome, tags, underground);
    }
}
