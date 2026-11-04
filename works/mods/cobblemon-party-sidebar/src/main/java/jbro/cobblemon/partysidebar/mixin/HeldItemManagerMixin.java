package jbro.cobblemon.partysidebar.mixin;

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.helditem.BaseCobblemonHeldItemManager;
import jbro.cobblemon.partysidebar.preset.PresetServerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon applies a battle's item changes to the real Pokemon at once: {@code take} removes a used-up or stolen item,
 * {@code give} replaces it (Trick, Thief). With {@code consumeStoredItemsInBattle} off, a borrowed storage item is left
 * where it is; the battle itself goes on without it.
 */
@Mixin(value = BaseCobblemonHeldItemManager.class, remap = false)
public abstract class HeldItemManagerMixin {
    @Inject(method = "take", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$keepBorrowedOnTake(BattlePokemon pokemon, String showdownId, CallbackInfo ci) {
        if (!PresetServerConfig.battleMayChange(pokemon, "take")) ci.cancel();
    }

    @Inject(method = "give", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$keepBorrowedOnGive(BattlePokemon pokemon, String showdownId, CallbackInfo ci) {
        if (!PresetServerConfig.battleMayChange(pokemon, "give")) ci.cancel();
    }
}
