package jbro.cobblemon.policy.mixin;

import jbro.cobblemon.policy.chat.ChatRankBadge;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Only player chat changes; name tags, the tab list, whispers and other messages keep their vanilla look. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerChatRankMixin {
    @Shadow public ServerPlayer player;

    @ModifyArg(method = "broadcastChatMessage", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/players/PlayerList;broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V"),
        index = 2)
    private ChatType.Bound jbroPolicy$rankBadge(ChatType.Bound bound) {
        return ChatRankBadge.decorate(player, bound);
    }
}
