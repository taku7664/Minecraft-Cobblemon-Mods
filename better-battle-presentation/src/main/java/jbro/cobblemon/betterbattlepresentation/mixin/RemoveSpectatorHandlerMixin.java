package jbro.cobblemon.betterbattlepresentation.mixin;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.net.messages.server.battle.RemoveSpectatorPacket;
import com.cobblemon.mod.common.net.serverhandling.battle.RemoveSpectatorHandler;
import jbro.cobblemon.betterbattlepresentation.server.MegaShowdownDynamaxBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RemoveSpectatorHandler.class, remap = false)
abstract class RemoveSpectatorHandlerMixin {
    @Inject(method = "handle", at = @At("HEAD"))
    private void betterBattlePresentation$beforeSpectatorLeft(
        RemoveSpectatorPacket packet,
        MinecraftServer server,
        ServerPlayer spectator,
        CallbackInfo callbackInfo
    ) {
        var battle = BattleRegistry.getBattle(packet.getBattleId());
        if (battle != null && battle.getSpectators().contains(spectator.getUUID())) {
            MegaShowdownDynamaxBridge.spectatorLeft(packet.getBattleId(), spectator.getUUID());
        }
    }
}
