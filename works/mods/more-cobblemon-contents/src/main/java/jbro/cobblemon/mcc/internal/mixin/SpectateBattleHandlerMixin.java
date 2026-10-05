package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.net.serverhandling.battle.SpectateBattleHandler;
import jbro.cobblemon.mcc.internal.battle.ManagedBattleContentNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a spectator the battle's tag before Cobblemon sends the battle, so music and UI mods treat the watched battle
 * as its content (a league Champion, a Tower boss) and not as a plain trainer battle. Covers every way in: the
 * interaction wheel, Cobblemon's command and MCC's remote spectating.
 */
@Mixin(value = SpectateBattleHandler.class, remap = false)
abstract class SpectateBattleHandlerMixin {
    @Inject(method = "spectateBattle", at = @At("HEAD"))
    private void mcc$showTagBeforeSpectating(ServerPlayer target, ServerPlayer viewer, CallbackInfo callbackInfo) {
        ManagedBattleContentNetworking.INSTANCE.showBeforeSpectating(target, viewer);
    }

    @Inject(method = "spectateBattle", at = @At("RETURN"))
    private void mcc$withdrawTagIfRefused(ServerPlayer target, ServerPlayer viewer, CallbackInfo callbackInfo) {
        ManagedBattleContentNetworking.INSTANCE.withdrawUnlessSpectating(target, viewer);
    }
}
