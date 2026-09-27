package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedBattleEntityPersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk storage writes an entity only when {@code save} (and therefore {@code saveAsPassenger}) returns
 * true, so refusing here keeps battle clones and MCC battle Pokemon out of pause saves, autosaves,
 * chunk unloads and the final save of a stopping server.
 */
@Mixin(Entity.class)
abstract class EntityManagedBattlePersistenceMixin {
    @Inject(method = "saveAsPassenger", at = @At("HEAD"), cancellable = true)
    private void mcc$skipManagedBattlePokemon(CompoundTag tag, CallbackInfoReturnable<Boolean> callbackInfo) {
        if ((Object) this instanceof PokemonEntity pokemonEntity
            && Cobblemon173ManagedBattleEntityPersistence.isTransient(pokemonEntity)) {
            callbackInfo.setReturnValue(false);
        }
    }
}
