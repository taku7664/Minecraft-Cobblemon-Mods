package jbro.cobblemon.dimensions.mixin;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import jbro.cobblemon.dimensions.worldgen.WorldgenPack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every server data pack list (a world being loaded, or one being created) gets {@link WorldgenPack}, the dimensions
 * built at startup. Loading a saved world goes through this overload too.
 */
@Mixin(ServerPacksSource.class)
abstract class ServerPacksSourceMixin {
    @Inject(method = "createPackRepository(Ljava/nio/file/Path;Lnet/minecraft/world/level/validation/DirectoryValidator;)Lnet/minecraft/server/packs/repository/PackRepository;",
            at = @At("RETURN"))
    private static void cobblemonDimensions$addWorldgen(Path path, DirectoryValidator validator, CallbackInfoReturnable<PackRepository> cir) {
        PackRepositoryAccessor repository = (PackRepositoryAccessor) cir.getReturnValue();
        Set<RepositorySource> sources = new LinkedHashSet<>(repository.cobblemonDimensions$sources());
        sources.add(WorldgenPack.INSTANCE);
        repository.cobblemonDimensions$setSources(sources);
    }
}
