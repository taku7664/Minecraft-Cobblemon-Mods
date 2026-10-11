package jbro.cobblemon.partysidebar.compat

import dev.emi.emi.api.EmiExclusionArea
import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.widget.Bounds
import jbro.cobblemon.partysidebar.client.PartySidebar
import net.minecraft.client.gui.screens.inventory.InventoryScreen

/** Keeps EMI's side panels (favourites, the item list) from covering the party column. Loaded only with EMI. */
class PartySidebarEmiPlugin : EmiPlugin {
    override fun register(registry: EmiRegistry) {
        registry.addExclusionArea(InventoryScreen::class.java, EmiExclusionArea { screen, area ->
            PartySidebar.bounds(screen)?.let { area.accept(Bounds(it.x, it.y, it.width, it.height)) }
        })
    }
}
