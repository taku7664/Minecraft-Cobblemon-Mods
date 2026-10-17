package jbro.minecraft.simpletrashcan;

import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/**
 * The trash slot of the survival inventory, outside the panel on the right, level with the last inventory row.
 * Replacing its item is handled in {@code AbstractContainerMenuMixin}.
 */
public final class TrashSlot extends Slot {
    /** Panel and slot positions relative to the inventory screen's top-left corner. */
    public static final int PANEL_X = 178;
    public static final int PANEL_Y = 101;
    public static final int PANEL_WIDTH = 36;
    public static final int PANEL_HEIGHT = 45;
    public static final int X = PANEL_X + (PANEL_WIDTH - 16) / 2;
    public static final int Y = 120;

    private static final ResourceLocation EMPTY_ICON = SimpleTrashCan.id("item/empty_slot_trash");

    public TrashSlot(TrashContainer container) {
        super(container, 0, X, Y);
    }

    @Override
    public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
        return Pair.of(InventoryMenu.BLOCK_ATLAS, EMPTY_ICON);
    }
}
