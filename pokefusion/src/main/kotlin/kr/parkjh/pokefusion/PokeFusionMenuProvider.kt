package kr.parkjh.pokefusion

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

class PokeFusionMenuProvider(private val serverPlayer: ServerPlayer) : MenuProvider {
    override fun createMenu(syncId: Int, inventory: Inventory, player: Player): AbstractContainerMenu {
        return PokeFusionMenu(syncId, inventory, serverPlayer)
    }

    override fun getDisplayName(): Component = Component.literal("포켓몬 합성")
}
