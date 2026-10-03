package jbro.cobblemon.mcc.internal.mixin;

import io.netty.channel.Channel;
import jbro.cobblemon.mcc.internal.wiki.WikiPortSharing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the wiki answer HTTP on the game port; see {@link WikiPortSharing}. */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
abstract class ServerConnectionListenerChannelMixin {
    @Inject(method = "initChannel", at = @At("TAIL"))
    private void mcc$sniffWikiRequests(Channel channel, CallbackInfo callbackInfo) {
        WikiPortSharing.install(channel);
    }
}
