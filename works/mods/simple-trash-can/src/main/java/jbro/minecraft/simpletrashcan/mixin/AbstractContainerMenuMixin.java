package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.TrashLog;
import jbro.minecraft.simpletrashcan.TrashSlot;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Shadow
    public abstract void setCarried(ItemStack stack);

    /**
     * Putting an item on an occupied trash slot: a different item replaces the old one, which is deleted; the same
     * item stacks onto it. Both are done here rather than left to vanilla, which would swap the old item back out (a
     * number key always swaps) or let the cursor item's own click behaviour (a bundle) run on the emptied slot and
     * place nothing. Taking the item out stays vanilla's. Runs on both sides, so the client's prediction matches.
     */
    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void simpleTrashCan$replaceTrash(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (slotId < 0 || slotId >= slots.size()) return;
        Slot slot = slots.get(slotId);
        if (!(slot instanceof TrashSlot) || !slot.hasItem()) return;
        ItemStack old = slot.getItem();
        if (clickType == ClickType.PICKUP) {
            ItemStack carried = getCarried();
            // Same item: vanilla stacks it; empty cursor: vanilla picks the trash item up.
            if (carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, old)) return;
            simpleTrashCan$logReplaced(player, old);
            if (button == 0) {
                slot.setByPlayer(carried);
                setCarried(ItemStack.EMPTY);
            } else {
                slot.setByPlayer(carried.split(1));
            }
            ci.cancel();
        } else if (clickType == ClickType.SWAP && (button >= 0 && button < 9 || button == 40)) {
            // A number key or the offhand key puts that inventory slot's item in. The button range is vanilla's own
            // check: the server must not index the inventory with whatever a client sends.
            Inventory inventory = player.getInventory();
            ItemStack held = inventory.getItem(button);
            if (held.isEmpty()) return;
            if (ItemStack.isSameItemSameComponents(held, old)) {
                int moved = Math.min(held.getCount(), slot.getMaxStackSize(old) - old.getCount());
                if (moved > 0) {
                    old.grow(moved);
                    held.shrink(moved);
                    slot.setChanged();
                }
            } else {
                simpleTrashCan$logReplaced(player, old);
                slot.setByPlayer(held);
                inventory.setItem(button, ItemStack.EMPTY);
            }
            ci.cancel();
        }
    }

    // The click runs on both sides; only the server's copy is the real deletion.
    @Unique
    private static void simpleTrashCan$logReplaced(Player player, ItemStack old) {
        if (player instanceof ServerPlayer serverPlayer) TrashLog.deleted(serverPlayer, old, TrashLog.Reason.REPLACED);
    }
}
