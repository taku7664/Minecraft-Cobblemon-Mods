package jbro.cobblemon.partysidebar.client

import com.mojang.blaze3d.platform.InputConstants
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.KeyMapping
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.glfw.GLFW

/** The sidebar itself is drawn and driven from the inventory screen; see [PartySidebar]. */
object CobblemonPartySidebarClient : ClientModInitializer {
    /** Pressed over a Pokemon in the column, turns it into an item. Read only while the inventory is open. */
    val ITEMIZE = KeyMapping(
        "key.cobblemon_party_sidebar.itemize",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_I,
        "key.categories.cobblemon_party_sidebar",
    )

    override fun onInitializeClient() {
        SidebarConfig.load()
        KeyBindingHelper.registerKeyBinding(ITEMIZE)
        // A different resource pack can bring a different inventory texture to measure.
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId() = CobblemonPartySidebar.id("inventory_skin")

            override fun onResourceManagerReload(manager: ResourceManager) = InventorySkin.invalidate()
        })
    }
}
