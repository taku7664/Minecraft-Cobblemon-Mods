package jbro.cobblemon.ui.extended.mixin;

import jbro.cobblemon.ui.extended.CinematicLetterbox;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat and the action-bar line step aside while the cinematic letterbox is up; they come back with the picture. */
@Mixin(Gui.class)
abstract class CinematicGuiMixin {
    @Inject(method = "renderChat", at = @At("HEAD"), cancellable = true)
    private void cobblemonUi$hideChatInScene(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CinematicLetterbox.isActive()) ci.cancel();
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void cobblemonUi$hideActionBarInScene(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CinematicLetterbox.isActive()) ci.cancel();
    }
}
