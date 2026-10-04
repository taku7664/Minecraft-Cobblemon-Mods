package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.util.LocalizationUtilsKt;
import jbro.cobblemon.mcc.internal.battle.BattleEffectNames;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every Cobblemon battle line is built here; see {@link BattleEffectNames} for the names it translates. */
@Mixin(LocalizationUtilsKt.class)
abstract class BattleLangMixin {
    @Inject(method = "battleLang", at = @At("HEAD"), remap = false)
    private static void mcc$translateEffectNames(String key, Object[] objects, CallbackInfoReturnable<MutableComponent> callbackInfo) {
        BattleEffectNames.localize(key, objects);
    }
}
