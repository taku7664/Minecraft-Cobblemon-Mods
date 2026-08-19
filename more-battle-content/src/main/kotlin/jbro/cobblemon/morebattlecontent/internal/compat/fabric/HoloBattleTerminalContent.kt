@file:Suppress("DEPRECATION")

package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.internal.terminal.TerminalInteractionResult
import jbro.cobblemon.morebattlecontent.internal.terminal.TerminalInteractionService
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour

internal object HoloBattleTerminalContent {
    val id: ResourceLocation = HoloBattleTerminalIds.id
    val block = HoloBattleTerminalBlock(
        BlockBehaviour.Properties.of()
            .strength(3.5f)
            .sound(SoundType.METAL)
            .lightLevel { 10 }
            .noOcclusion(),
    )
    lateinit var item: BlockItem
        private set
    lateinit var blockEntityType: BlockEntityType<HoloBattleTerminalBlockEntity>
        private set
    val interactions = TerminalInteractionService()

    private var registered = false
    private var opener: (ServerPlayer, TerminalInteractionResult.Verified) -> Boolean = { _, _ -> false }

    fun register(opener: (ServerPlayer, TerminalInteractionResult.Verified) -> Boolean) {
        check(!registered) { "Holo Battle Terminal content was registered twice" }
        registered = true
        this.opener = opener
        Registry.register(BuiltInRegistries.BLOCK, id, block)
        item = Registry.register(BuiltInRegistries.ITEM, id, BlockItem(block, Item.Properties()))
        blockEntityType = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            id,
            FabricBlockEntityTypeBuilder.create(::HoloBattleTerminalBlockEntity, block).build(),
        )
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register { entries ->
            entries.accept(item)
        }
    }

    fun open(player: ServerPlayer, verification: TerminalInteractionResult.Verified): Boolean =
        opener(player, verification)
}
