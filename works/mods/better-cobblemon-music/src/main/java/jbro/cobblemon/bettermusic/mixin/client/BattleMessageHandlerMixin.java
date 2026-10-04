package jbro.cobblemon.bettermusic.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleMessageHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import jbro.cobblemon.bettermusic.audio.ClientHitSoundTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleMessageHandler.class)
public abstract class BattleMessageHandlerMixin {
    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleMessagePacket;Lnet/minecraft/client/Minecraft;)V",
        at = @At("HEAD")
    )
    private void betterCobblemonMusic$trackBattleMessages(
        BattleMessagePacket packet,
        Minecraft client,
        CallbackInfo callbackInfo
    ) {
        packet.getMessages().forEach(message -> {
            if (message.getContents() instanceof TranslatableContents translatable) {
                ClientHitSoundTracker.INSTANCE.onBattleMessageKey(translatable.getKey());
            }
        });
    }
}
