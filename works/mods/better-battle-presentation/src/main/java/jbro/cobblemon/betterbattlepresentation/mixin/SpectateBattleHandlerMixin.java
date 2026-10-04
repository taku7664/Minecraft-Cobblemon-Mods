package jbro.cobblemon.betterbattlepresentation.mixin;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.net.serverhandling.battle.SpectateBattleHandler;
import jbro.cobblemon.betterbattlepresentation.server.MegaShowdownDynamaxBridge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SpectateBattleHandler.class, remap = false)
abstract class SpectateBattleHandlerMixin {
    @Inject(method = "spectateBattle", at = @At("TAIL"))
    private void betterBattlePresentation$afterSpectatorJoined(
        ServerPlayer target,
        ServerPlayer spectator,
        CallbackInfo callbackInfo
    ) {
        var battle = BattleRegistry.getBattleByParticipatingPlayer(target);
        if (battle != null && battle.getSpectators().contains(spectator.getUUID())) {
            MegaShowdownDynamaxBridge.spectatorJoined(battle, spectator.getUUID());
        }
    }
}
