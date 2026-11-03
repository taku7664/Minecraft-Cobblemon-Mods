package jbro.cobblemon.battlespeed.mixin;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.net.serverhandling.battle.SpectateBattleHandler;
import jbro.cobblemon.battlespeed.server.BattleSpeedService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A spectator who joins mid-battle gets the battle's HUD speed too. */
@Mixin(value = SpectateBattleHandler.class, remap = false)
abstract class SpectateBattleHandlerMixin {
    @Inject(method = "spectateBattle", at = @At("TAIL"))
    private void battleSpeed$afterSpectatorJoined(ServerPlayer target, ServerPlayer spectator, CallbackInfo callback) {
        var battle = BattleRegistry.getBattleByParticipatingPlayer(target);
        if (battle != null && battle.getSpectators().contains(spectator.getUUID())) {
            BattleSpeedService.send(spectator.getUUID(), battle);
        }
    }
}
