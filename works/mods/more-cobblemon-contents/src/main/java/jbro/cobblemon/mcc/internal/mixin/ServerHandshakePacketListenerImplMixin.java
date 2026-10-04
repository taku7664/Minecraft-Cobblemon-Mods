package jbro.cobblemon.mcc.internal.mixin;

import jbro.cobblemon.mcc.internal.wiki.WikiPortSharing;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers the address each client typed, so wiki links point where that player can reach. */
@Mixin(ServerHandshakePacketListenerImpl.class)
abstract class ServerHandshakePacketListenerImplMixin {
    @Shadow @Final private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void mcc$rememberAddress(ClientIntentionPacket packet, CallbackInfo callbackInfo) {
        WikiPortSharing.rememberAddress(connection, packet.hostName(), packet.port());
    }
}
