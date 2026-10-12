package jbro.cobblemon.partysidebar.mixin.client;

import jbro.cobblemon.partysidebar.client.PartySidebar;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional fusion support uses its registered menu ID, without loading PokeFusion classes. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    private AbstractContainerScreen<?> cobblemonPartySidebar$self() {
        return (AbstractContainerScreen<?>) (Object) this;
    }

    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void cobblemonPartySidebar$column(GuiGraphics graphics, int x, int y, float tick, CallbackInfo ci) {
        if (!((Object) this instanceof InventoryScreen)) PartySidebar.INSTANCE.renderColumn(cobblemonPartySidebar$self(), graphics, x, y);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void cobblemonPartySidebar$overlay(GuiGraphics graphics, int x, int y, float tick, CallbackInfo ci) {
        if (!((Object) this instanceof InventoryScreen)) PartySidebar.INSTANCE.renderOverlay(cobblemonPartySidebar$self(), graphics, x, y);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$click(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof InventoryScreen) && PartySidebar.INSTANCE.mouseClicked(cobblemonPartySidebar$self(), x, y, button)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$release(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof InventoryScreen) && PartySidebar.INSTANCE.mouseReleased(cobblemonPartySidebar$self(), x, y, button)) cir.setReturnValue(true);
    }

    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$outside(double x, double y, int left, int top, int button, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof InventoryScreen) && PartySidebar.INSTANCE.contains(cobblemonPartySidebar$self(), x, y)) cir.setReturnValue(false);
    }
}
