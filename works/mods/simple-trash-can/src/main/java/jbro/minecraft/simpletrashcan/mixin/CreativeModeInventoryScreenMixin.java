package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.TrashContainer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeInventoryScreen.class)
abstract class CreativeModeInventoryScreenMixin {
    /**
     * The creative inventory tab copies every survival slot and would lay the trash slot over the hotbar. Creative has
     * its own delete slot, so the trash slot is left out there.
     */
    @Inject(method = "selectTab", at = @At("TAIL"))
    private void simpleTrashCan$hideTrash(CreativeModeTab tab, CallbackInfo ci) {
        var slots = ((AbstractContainerScreen<?>) (Object) this).getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container instanceof TrashContainer) {
                slots.set(i, new Slot(slot.container, 0, -2000, -2000) {
                    @Override
                    public boolean isActive() {
                        return false;
                    }
                });
            }
        }
    }
}
