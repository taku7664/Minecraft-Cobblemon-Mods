package jbro.cobblemon.bettermusic.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleFaintHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleFaintPacket;
import jbro.cobblemon.bettermusic.client.LastPokemonMuffleTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleFaintHandler.class)
public abstract class BattleFaintHandlerMixin {
    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleFaintPacket;Lnet/minecraft/client/Minecraft;)V",
        at = @At("HEAD")
    )
    private void betterCobblemonMusic$trackPlayerFaint(
        BattleFaintPacket packet,
        Minecraft client,
        CallbackInfo callbackInfo
    ) {
        LastPokemonMuffleTracker.INSTANCE.onFaint(client, packet.getPnx());
    }
}
