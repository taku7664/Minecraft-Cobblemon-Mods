package jbro.cobblemon.morebattlecontent.internal.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173ManagedTrainerPokemonOwners;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Pokemon.class)
abstract class PokemonMixin {
    @Inject(method = "getOwnerEntity", at = @At("RETURN"), remap = false, cancellable = true)
    private void mbc$resolveManagedTrainerOwner(CallbackInfoReturnable<LivingEntity> callbackInfo) {
        if (callbackInfo.getReturnValue() != null) {
            return;
        }
        LivingEntity owner = Cobblemon173ManagedTrainerPokemonOwners.resolve((Pokemon) (Object) this);
        if (owner != null) {
            callbackInfo.setReturnValue(owner);
        }
    }
}
