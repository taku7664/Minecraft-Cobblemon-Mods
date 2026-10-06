package jbro.cobblemon.ui.extended.mixin;

import jbro.cobblemon.ui.extended.CinematicLetterbox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Toasts wait out a scene instead of covering the top bar; they keep their queue and show afterwards. */
@Mixin(ToastComponent.class)
abstract class CinematicToastMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobblemonUi$holdToastsInScene(GuiGraphics graphics, CallbackInfo ci) {
        if (CinematicLetterbox.isActive()) ci.cancel();
    }
}
