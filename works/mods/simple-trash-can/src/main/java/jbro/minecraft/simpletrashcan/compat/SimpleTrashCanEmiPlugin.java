package jbro.minecraft.simpletrashcan.compat;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;
import jbro.minecraft.simpletrashcan.TrashSlot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/** Keeps EMI's item list from covering the trash panel. Loaded only with EMI. */
public final class SimpleTrashCanEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addExclusionArea(InventoryScreen.class, (screen, area) -> area.accept(new Bounds(
            screen.leftPos + TrashSlot.PANEL_X, screen.topPos + TrashSlot.PANEL_Y, TrashSlot.PANEL_WIDTH, TrashSlot.PANEL_HEIGHT)));
    }
}
