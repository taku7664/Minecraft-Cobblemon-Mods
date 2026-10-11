package jbro.cobblemon.partysidebar.mixin.client;

import jbro.cobblemon.partysidebar.client.PartySidebar;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the party column into the survival inventory. The column is drawn with the background, under the slots'
 * highlights and the cursor item; its tooltip and a dragged Pokemon go on top once the screen has drawn.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    private InventoryScreen cobblemonPartySidebar$self() {
        return (InventoryScreen) (Object) this;
    }

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void cobblemonPartySidebar$renderColumn(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        PartySidebar.INSTANCE.renderColumn(cobblemonPartySidebar$self(), graphics, mouseX, mouseY);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void cobblemonPartySidebar$renderOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        PartySidebar.INSTANCE.renderOverlay(cobblemonPartySidebar$self(), graphics, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (PartySidebar.INSTANCE.mouseClicked(cobblemonPartySidebar$self(), mouseX, mouseY, button)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$mouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (PartySidebar.INSTANCE.mouseReleased(cobblemonPartySidebar$self(), mouseX, mouseY, button)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (PartySidebar.INSTANCE.keyPressed(cobblemonPartySidebar$self(), keyCode, scanCode)) cir.setReturnValue(true);
    }

    /** A click on the column is not a click outside the inventory, which would throw the cursor item. */
    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void cobblemonPartySidebar$hasClickedOutside(double mouseX, double mouseY, int left, int top, int button, CallbackInfoReturnable<Boolean> cir) {
        if (PartySidebar.INSTANCE.contains(cobblemonPartySidebar$self(), mouseX, mouseY)) cir.setReturnValue(false);
    }
}
