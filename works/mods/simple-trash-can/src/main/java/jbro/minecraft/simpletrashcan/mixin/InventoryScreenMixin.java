package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.client.TrashPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryScreen.class)
abstract class InventoryScreenMixin extends AbstractContainerScreen<InventoryMenu> {
    private InventoryScreenMixin(InventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void simpleTrashCan$renderPanel(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        TrashPanel.render(graphics, leftPos, topPos);
    }

    // The panel sits outside the inventory's bounds; a click on it must reach the slot instead of dropping the cursor item.
    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void simpleTrashCan$panelIsInside(double mouseX, double mouseY, int left, int top, int button, CallbackInfoReturnable<Boolean> cir) {
        if (TrashPanel.contains(left, top, mouseX, mouseY)) cir.setReturnValue(false);
    }
}
