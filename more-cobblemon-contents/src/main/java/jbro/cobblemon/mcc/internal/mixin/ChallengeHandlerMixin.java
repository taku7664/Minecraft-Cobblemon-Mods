package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.net.messages.server.BattleChallengePacket;
import com.cobblemon.mod.common.net.serverhandling.ChallengeHandler;
import jbro.cobblemon.mcc.internal.battle.WildBattleEntryDelay;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds a wild challenge for the battle entry transition; see {@link WildBattleEntryDelay}. */
@Mixin(value = ChallengeHandler.class, remap = false)
abstract class ChallengeHandlerMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void mcc$holdForBattleEntry(
        BattleChallengePacket packet,
        MinecraftServer server,
        ServerPlayer player,
        CallbackInfo callbackInfo
    ) {
        if (WildBattleEntryDelay.intercept(packet, server, player)) {
            callbackInfo.cancel();
        }
    }
}
