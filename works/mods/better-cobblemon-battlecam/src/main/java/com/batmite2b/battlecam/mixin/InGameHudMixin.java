package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={Gui.class})
public abstract class InGameHudMixin {
    @Inject(method={"renderItemHotbar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideHotbar(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideCrosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderSelectedItemName(Lnet/minecraft/client/gui/GuiGraphics;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideHeldItemTooltip(GuiGraphics context, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderPlayerHealth(Lnet/minecraft/client/gui/GuiGraphics;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideStatusBars(GuiGraphics context, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderExperienceBar(Lnet/minecraft/client/gui/GuiGraphics;I)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideExperienceBar(GuiGraphics context, int x, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideExperienceLevel(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderVehicleHealth(Lnet/minecraft/client/gui/GuiGraphics;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideMountHealth(GuiGraphics context, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }
}
