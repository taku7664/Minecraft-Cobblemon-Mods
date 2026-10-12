package jbro.cobblemon.toitem.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.toitem.api.PokemonItemData
import jbro.cobblemon.toitem.network.RestoreInventoryPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen

object ToItemClient : ClientModInitializer {
    override fun onInitializeClient() {}

    @JvmStatic fun quickRestore(screen: InventoryScreen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 || !Screen.hasShiftDown() || !screen.menu.carried.isEmpty ||
            !ClientPlayNetworking.canSend(RestoreInventoryPayload.TYPE)) return false
        val player = Minecraft.getInstance().player ?: return false
        val slot = screen.menu.slots.firstOrNull {
            it.isActive && mouseX >= screen.leftPos + it.x && mouseX < screen.leftPos + it.x + 16 &&
                mouseY >= screen.topPos + it.y && mouseY < screen.topPos + it.y + 16
        } ?: return false
        if (slot.container !== player.inventory || slot.containerSlot !in 0 until 36) return false
        val pokemonTag = PokemonItemData.pokemonTag(slot.item) ?: return false
        // Consume this input even if restoration is blocked, so vanilla cannot quick-move the item instead.
        if (CobblemonClient.battle == null) {
            ClientPlayNetworking.send(RestoreInventoryPayload(slot.index, pokemonTag.getUUID("UUID")))
        }
        return true
    }
}
