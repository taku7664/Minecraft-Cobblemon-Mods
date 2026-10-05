package jbro.cobblemon.dimensions.mixin;

import jbro.cobblemon.dimensions.CobblemonDimensions;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Our dimensions borrow Terralith's overworld terrain functions. With the world seed they would come out the same
 * shape as the overworld at the same coordinates, so each of them gets its own terrain seed: the world seed mixed with
 * the dimension's name. Biomes are picked from the same noise, so they still fit the terrain. Structure placement keeps
 * the world seed.
 *
 * <p>Changing this after a world has generated leaves seams where old chunks meet new ones.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapSeedMixin {
    @Shadow @Final ServerLevel level;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomState;create(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/core/HolderGetter;J)Lnet/minecraft/world/level/levelgen/RandomState;"), index = 2, require = 1, allow = 2)
    private long cobblemonDimensions$ownTerrainSeed(long seed) {
        var id = level.dimension().location();
        if (!id.getNamespace().equals(CobblemonDimensions.MOD_ID)) return seed;
        // A fixed mix of the name, so the same world always gets the same terrain.
        return seed ^ (id.toString().hashCode() * 0x9E3779B97F4A7C15L);
    }
}
