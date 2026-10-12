package jbro.cobblemon.toitem.mixin.client;

import jbro.cobblemon.toitem.client.ToItemClient;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonToItem$quickRestore(double x, double y, int button, CallbackInfoReturnable<Boolean> ci) {
        if (ToItemClient.quickRestore((InventoryScreen) (Object) this, x, y, button)) ci.setReturnValue(true);
    }
}
