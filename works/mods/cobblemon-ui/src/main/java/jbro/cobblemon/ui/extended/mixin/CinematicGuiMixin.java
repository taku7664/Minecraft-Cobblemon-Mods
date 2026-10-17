package jbro.cobblemon.ui.extended.mixin;

import jbro.cobblemon.ui.extended.CinematicLetterbox;
import jbro.cobblemon.ui.extended.transition.BattleVersusIntro;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat and the action-bar line step aside while the cinematic letterbox is up; they come back with the picture. The
 * hotbar (with hearts and experience) steps aside too, and under the versus intro, where its items and counts
 * showed through the scene.
 */
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

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void cobblemonUi$hideHotbarInScene(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CinematicLetterbox.isActive() || BattleVersusIntro.getCovering()) ci.cancel();
    }
}
