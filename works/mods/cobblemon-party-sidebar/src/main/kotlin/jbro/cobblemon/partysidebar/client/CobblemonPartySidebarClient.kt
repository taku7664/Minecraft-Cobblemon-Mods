package jbro.cobblemon.partysidebar.client

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.PresetStatePayload
import jbro.cobblemon.partysidebar.preset.StorageMenu
import jbro.cobblemon.partysidebar.preset.StoredMark
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.inventory.ChestMenu

/** The sidebar itself is drawn and driven from the inventory screen; see [PartySidebar]. */
object CobblemonPartySidebarClient : ClientModInitializer {
    override fun onInitializeClient() {
        SidebarConfig.load()
        // A different resource pack can bring a different inventory texture to measure.
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId() = CobblemonPartySidebar.id("inventory_skin")

            override fun onResourceManagerReload(manager: ResourceManager) = InventorySkin.invalidate()
        })
        // The item storage is a chest window with a scroll bar; the party column docks beside it.
        MenuScreens.register<ChestMenu, StorageScreen>(StorageMenu.TYPE) { menu, inventory, title -> StorageScreen(menu as StorageMenu, inventory, title) }
        ClientPlayNetworking.registerGlobalReceiver(PresetStatePayload.TYPE) { payload, _ -> PresetClient.state = payload }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> PresetClient.state = null }
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (StoredMark.isStored(stack) && lines.isNotEmpty()) lines[0] = PresetClient.namePrefix().append(lines[0])
        }
    }
}
