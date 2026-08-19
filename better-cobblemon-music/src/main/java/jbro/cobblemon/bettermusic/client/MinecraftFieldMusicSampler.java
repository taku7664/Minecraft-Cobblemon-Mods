package jbro.cobblemon.bettermusic.client;

import java.util.Optional;
import java.util.stream.Collectors;
import jbro.cobblemon.bettermusic.field.FieldMusicContext;
import jbro.cobblemon.bettermusic.field.UndergroundDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;

public final class MinecraftFieldMusicSampler {
    private static final String FALLBACK_BIOME_ID = "minecraft:plains";

    public Optional<FieldMusicContext> sample(Minecraft client) {
        if (client.player == null || client.level == null) {
            return Optional.empty();
        }

        var level = client.level;
        var position = client.player.blockPosition();
        var biome = level.getBiome(position);
        String biomeId = biome.unwrapKey()
            .map(key -> key.location().toString())
            .orElse(FALLBACK_BIOME_ID);
        var biomeTags = biome.tags()
            .map(tag -> tag.location().toString())
            .collect(Collectors.toUnmodifiableSet());
        int surfaceY = level.getHeight(
            Heightmap.Types.WORLD_SURFACE,
            position.getX(),
            position.getZ()
        );
        boolean underground = UndergroundDetector.isUnderground(
            level.canSeeSky(position),
            surfaceY,
            position.getY()
        );

        return Optional.of(new FieldMusicContext(
            level.dimension().location().toString(),
            biomeId,
            biomeTags,
            underground
        ));
    }
}
