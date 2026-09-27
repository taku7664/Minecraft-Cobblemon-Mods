package jbro.cobblemon.mcc.pvp.mixin.client

import jbro.cobblemon.mcc.client.PvpInviteChatClickHandler
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Style
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Screen::class)
abstract class ScreenPvpInviteClickMixin {
    @Inject(method = ["handleComponentClicked"], at = [At("HEAD")], cancellable = true)
    private fun `mcc$handlePvpInviteClick`(style: Style?, callback: CallbackInfoReturnable<Boolean>) {
        if (PvpInviteChatClickHandler.handleInsertion(style?.insertion)) {
            callback.returnValue = true
        }
    }
}
