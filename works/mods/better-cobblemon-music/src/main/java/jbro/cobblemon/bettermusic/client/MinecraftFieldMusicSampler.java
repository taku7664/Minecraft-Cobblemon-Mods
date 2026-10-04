package jbro.cobblemon.bettermusic.client;

import java.util.Optional;
import java.util.stream.Collectors;
import jbro.cobblemon.bettermusic.field.FieldMusicContext;
import jbro.cobblemon.bettermusic.field.UndergroundDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;

public final class MinecraftFieldMusicSampler {
    private static final String FALLBACK_BIOME_ID = "minecraft:plains";
    // Vanilla computes this heightmap from the client chunk when it is not sent by the server.
    // Unlike WORLD_SURFACE, jungle leaves do not count as a cave roof.
    static final Heightmap.Types CAVE_COVER_HEIGHTMAP = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;

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
            CAVE_COVER_HEIGHTMAP,
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
            underground,
            FieldMusicContext.TimeOfDay.fromWorldTime(level.dimensionType().fixedTime().orElse(level.getDayTime()))
        ));
    }
}
