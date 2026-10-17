package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.TrashHolder;
import jbro.minecraft.simpletrashcan.TrashSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryMenu.class)
abstract class InventoryMenuMixin extends AbstractContainerMenu {
    private InventoryMenuMixin(MenuType<?> type, int id) {
        super(type, id);
    }

    // Added last, so every vanilla slot keeps its index. Vanilla shift-click only targets slots 9 to 44, so items go
    // into the trash by hand only, and shift-clicking the trash moves its item back into the inventory.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void simpleTrashCan$addTrashSlot(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        addSlot(new TrashSlot(((TrashHolder) owner).simpleTrashCan$trash()));
    }

    // A double-click gathering a stack does not pull from the trash.
    @Inject(method = "canTakeItemForPickAll", at = @At("HEAD"), cancellable = true)
    private void simpleTrashCan$skipTrash(ItemStack stack, Slot slot, CallbackInfoReturnable<Boolean> cir) {
        if (slot instanceof TrashSlot) cir.setReturnValue(false);
    }
}
