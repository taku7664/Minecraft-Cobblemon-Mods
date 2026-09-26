@file:Suppress("DEPRECATION")
package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import com.mojang.serialization.MapCodec
import java.util.UUID
import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge as Mod
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.*
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.*
import net.minecraft.world.phys.BlockHitResult

object LeagueTerminal {
    val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "league_terminal")
    val block = TerminalBlock(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL))
    lateinit var entityType: BlockEntityType<TerminalEntity>
    fun register() {
        Registry.register(BuiltInRegistries.BLOCK, id, block)
        val item = Registry.register(BuiltInRegistries.ITEM, id, BlockItem(block, Item.Properties()))
        entityType = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
            FabricBlockEntityTypeBuilder.create(::TerminalEntity, block).build())
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register { it.accept(item) }
    }
}

class TerminalBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = simpleCodec(::TerminalBlock)
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = TerminalEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        return if (LeagueServer.open(serverPlayer, pos)) InteractionResult.SUCCESS else InteractionResult.FAIL
    }
}

class TerminalEntity(pos: BlockPos, state: BlockState) : BlockEntity(LeagueTerminal.entityType, pos, state) {
    var terminalId: UUID = UUID.randomUUID()
        private set
    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putUUID("terminal_id", terminalId)
    }
    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.hasUUID("terminal_id")) terminalId = tag.getUUID("terminal_id")
    }
}
