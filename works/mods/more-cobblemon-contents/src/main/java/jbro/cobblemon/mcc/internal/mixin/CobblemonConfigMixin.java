package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.config.CobblemonConfig;
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173OpponentLevelScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CobblemonConfig.class)
abstract class CobblemonConfigMixin {
    /** A managed opponent built above the server's maximum level keeps its level (Cobblemon173OpponentLevelScope). */
    @Inject(method = "getMaxPokemonLevel", at = @At("RETURN"), remap = false, cancellable = true)
    private void mcc$raiseForManagedOpponent(CallbackInfoReturnable<Integer> callbackInfo) {
        Integer limit = Cobblemon173OpponentLevelScope.limit();
        if (limit != null && limit > callbackInfo.getReturnValue()) {
            callbackInfo.setReturnValue(limit);
        }
    }
}
