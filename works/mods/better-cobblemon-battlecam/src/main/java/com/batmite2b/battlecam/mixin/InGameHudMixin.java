/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.gui.DrawContext
 *  net.minecraft.client.gui.hud.InGameHud
 *  net.minecraft.client.render.RenderTickCounter
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={InGameHud.class})
public abstract class InGameHudMixin {
    @Inject(method={"renderHotbar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderHeldItemTooltip(Lnet/minecraft/client/gui/DrawContext;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideHeldItemTooltip(DrawContext context, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderStatusBars(Lnet/minecraft/client/gui/DrawContext;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideStatusBars(DrawContext context, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderExperienceBar(Lnet/minecraft/client/gui/DrawContext;I)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideExperienceBar(DrawContext context, int x, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderExperienceLevel(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideExperienceLevel(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }

    @Inject(method={"renderMountHealth(Lnet/minecraft/client/gui/DrawContext;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$hideMountHealth(DrawContext context, CallbackInfo ci) {
        if (BattleCamClient.STATE.shouldOverrideCamera()) {
            ci.cancel();
        }
    }
}
