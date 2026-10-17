package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.TrashSlot;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
abstract class AbstractContainerMenuMixin {
    @Shadow
    @Final
    public NonNullList<Slot> slots;

    @Shadow
    public abstract ItemStack getCarried();

    /**
     * Putting a different item on an occupied trash slot deletes the old one first, so vanilla then just places the new
     * item instead of swapping the old one onto the cursor. The same item still stacks onto it. Runs on both sides,
     * so the client's prediction matches the server.
     */
    @Inject(method = "doClick", at = @At("HEAD"))
    private void simpleTrashCan$replaceTrash(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (slotId < 0 || slotId >= slots.size()) return;
        Slot slot = slots.get(slotId);
        if (!(slot instanceof TrashSlot) || !slot.hasItem()) return;
        ItemStack incoming = switch (clickType) {
            case PICKUP -> getCarried();
            // A number key or the offhand key swaps that inventory slot's item in.
            case SWAP -> player.getInventory().getItem(button);
            default -> ItemStack.EMPTY;
        };
        if (!incoming.isEmpty() && !ItemStack.isSameItemSameComponents(incoming, slot.getItem())) {
            slot.set(ItemStack.EMPTY);
        }
    }
}
