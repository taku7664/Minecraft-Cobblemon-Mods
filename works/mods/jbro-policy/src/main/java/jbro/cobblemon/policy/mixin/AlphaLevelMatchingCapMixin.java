package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import jbro.cobblemon.policy.wild.WildPokemonPolicy;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wild alphas match the nearest player's level, but stay inside that player's League wild spawn band there. */
@Mixin(targets = "com.cobblemon.mod.common.entity.pokemon.ai.sensors.AlphaLevelMatchingSensor$Companion", remap = false)
abstract class AlphaLevelMatchingCapMixin {
    @Inject(method = "getTargetLevel", at = @At("RETURN"), cancellable = true)
    private void jbroPolicy$capAtLeagueLevel(Pokemon pokemon, ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        Integer matched = cir.getReturnValue();
        if (matched == null) return;
        int capped = WildPokemonPolicy.capAlphaLevel(matched, pokemon, player);
        if (capped != matched) cir.setReturnValue(capped);
    }
}
