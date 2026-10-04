package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import jbro.cobblemon.policy.pokemon.PokemonSummaryOnInteract;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Right-clicking your own sent-out Pokemon opens its summary when Cobblemon found nothing else to do with the click. */
@Mixin(PokemonEntity.class)
abstract class PokemonEntitySummaryMixin {
    @Inject(method = "mobInteract", at = @At("RETURN"))
    private void jbroPolicy$openSummary(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue().consumesAction()) return;
        PokemonSummaryOnInteract.onUnhandledInteract((PokemonEntity) (Object) this, player, hand);
    }
}
