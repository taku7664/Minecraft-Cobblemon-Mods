package jbro.cobblemon.partysidebar.compat

import dev.emi.emi.api.EmiExclusionArea
import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.widget.Bounds
import jbro.cobblemon.partysidebar.client.PartySidebar
import jbro.cobblemon.partysidebar.client.StorageScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Keeps EMI's side panels (favourites, the item list) off the party column and the storage scroll bar. Loaded only with EMI. */
class PartySidebarEmiPlugin : EmiPlugin {
    override fun register(registry: EmiRegistry) {
        registry.addExclusionArea(AbstractContainerScreen::class.java, EmiExclusionArea { screen, area ->
            PartySidebar.bounds(screen)?.let { area.accept(Bounds(it.x, it.y, it.width, it.height)) }
            if (screen is StorageScreen) screen.scrollPanel().let { (x, y, width, height) -> area.accept(Bounds(x, y, width, height)) }
        })
    }
}
