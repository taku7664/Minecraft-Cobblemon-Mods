@file:Suppress("DEPRECATION")

package jbro.cobblemon.mcc.api.terminal

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.compat.fabric.HoloTerminalBlock
import jbro.cobblemon.mcc.internal.compat.fabric.HoloTerminalBlockEntity
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour

/** The colours of a hologram terminal, each 0xRRGGBB; the renderer keeps its own translucency per part. */
data class HoloTerminalPalette(
    val base: Int,
    val pillar: Int,
    val outerRing: Int,
    val innerRing: Int,
    val crystal: Int,
    val crystalEdge: Int,
    val scan: Int,
) {
    companion object {
        /** The cyan of the MCC battle terminal. */
        val MCC = HoloTerminalPalette(0x124E60, 0x1A94A8, 0x2CE2EE, 0x72F5FF, 0x69F4FF, 0x3ABEE6, 0x50E8F8)
    }
}

/**
 * A hologram terminal block. Using it opens the MCC hub on the tabs the server's hub tab config lists for [id],
 * [defaultTabs] until an admin changes them. The hub starts on the dashboard, like `/mcc`.
 */
class HoloTerminal internal constructor(
    val id: ResourceLocation,
    val defaultTabs: List<String>,
    val palette: HoloTerminalPalette,
) {
    internal val holoBlock = HoloTerminalBlock(
        BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).lightLevel { 10 }.noOcclusion(),
        this,
    )
    val block: Block get() = holoBlock
    lateinit var item: BlockItem
        private set
    lateinit var blockEntityType: BlockEntityType<*>
        private set

    internal fun register() {
        Registry.register(BuiltInRegistries.BLOCK, id, holoBlock)
        item = Registry.register(BuiltInRegistries.ITEM, id, BlockItem(holoBlock, Item.Properties()))
        blockEntityType = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            id,
            FabricBlockEntityTypeBuilder.create({ position, state -> HoloTerminalBlockEntity(this, position, state) }, holoBlock).build(),
        )
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register { entries -> entries.accept(item) }
    }
}

/** The hologram terminals of MCC and its content mods. Register them while mods initialize. */
object HoloTerminals {
    private val terminals = CopyOnWriteArrayList<HoloTerminal>()

    fun register(id: ResourceLocation, defaultTabs: List<String>, palette: HoloTerminalPalette): HoloTerminal {
        require(terminals.none { it.id == id }) { "Duplicate hologram terminal: $id" }
        require(defaultTabs.isNotEmpty() && defaultTabs.distinct().size == defaultTabs.size && defaultTabs.all(ManagedBattleContentIds::isValid)) {
            "Invalid default tabs for $id: $defaultTabs"
        }
        return HoloTerminal(id, defaultTabs.toList(), palette).also {
            it.register()
            terminals += it
        }
    }

    fun all(): List<HoloTerminal> = terminals.toList()

    /** The identity of the hologram terminal at [position], or null when none stands there. */
    fun terminalIdAt(level: Level, position: BlockPos): UUID? {
        val entity = level.getBlockEntity(position) as? HoloTerminalBlockEntity ?: return null
        return entity.terminalId.takeIf { level.getBlockState(position).`is`(entity.terminal.block) }
    }
}
