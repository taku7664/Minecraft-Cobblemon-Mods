package jbro.cobblemon.bettermusic.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleUpdateTeamPokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleUpdateTeamPokemonPacket;
import jbro.cobblemon.bettermusic.client.LastPokemonMuffleTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleUpdateTeamPokemonHandler.class)
public abstract class BattleUpdateTeamPokemonHandlerMixin {
    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleUpdateTeamPokemonPacket;Lnet/minecraft/client/Minecraft;)V",
        at = @At("RETURN")
    )
    private void betterCobblemonMusic$refreshPlayerPokemonState(
        BattleUpdateTeamPokemonPacket packet,
        Minecraft client,
        CallbackInfo callbackInfo
    ) {
        LastPokemonMuffleTracker.INSTANCE.onTeamPokemonUpdate(client, packet.getPokemon());
    }
}
