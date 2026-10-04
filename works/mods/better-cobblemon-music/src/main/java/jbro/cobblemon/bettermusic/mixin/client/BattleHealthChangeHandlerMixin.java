package jbro.cobblemon.bettermusic.mixin.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.net.battle.BattleHealthChangeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import jbro.cobblemon.bettermusic.audio.ClientHitSoundTracker;
import jbro.cobblemon.bettermusic.client.BattleHitSoundPlayer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleHealthChangeHandler.class)
public abstract class BattleHealthChangeHandlerMixin {
    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleHealthChangePacket;Lnet/minecraft/client/Minecraft;)V",
        at = @At("HEAD")
    )
    private void betterCobblemonMusic$playHitSound(
        BattleHealthChangePacket packet,
        Minecraft client,
        CallbackInfo callbackInfo
    ) {
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            return;
        }

        for (var side : battle.getSides()) {
            for (var activePokemon : side.getActiveClientBattlePokemon()) {
                if (!packet.getPnx().equals(activePokemon.getPNX())) {
                    continue;
                }
                var battlePokemon = activePokemon.getBattlePokemon();
                if (battlePokemon != null) {
                    ClientHitSoundTracker.INSTANCE.onHealthChange(
                        packet.getPnx(),
                        battlePokemon.getHpValue(),
                        packet.getNewHealth()
                    ).ifPresent(BattleHitSoundPlayer::play);
                }
                return;
            }
        }
    }
}
