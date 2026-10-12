package jbro.cobblemon.partysidebar.client

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

/** The sidebar itself is drawn and driven from the inventory screen; see [PartySidebar]. */
object CobblemonPartySidebarClient : ClientModInitializer {
    override fun onInitializeClient() {
        SidebarConfig.load()
        // A different resource pack can bring a different inventory texture to measure.
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId() = CobblemonPartySidebar.id("inventory_skin")

            override fun onResourceManagerReload(manager: ResourceManager) = InventorySkin.invalidate()
        })
    }
}
