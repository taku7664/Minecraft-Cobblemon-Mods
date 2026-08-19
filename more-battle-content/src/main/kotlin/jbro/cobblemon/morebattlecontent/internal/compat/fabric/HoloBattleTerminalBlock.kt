package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import com.mojang.serialization.MapCodec
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.terminal.TerminalInteractionResult
import jbro.cobblemon.morebattlecontent.internal.terminal.TerminalInteractionSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

internal class HoloBattleTerminalBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun newBlockEntity(position: BlockPos, state: BlockState): BlockEntity =
        HoloBattleTerminalBlockEntity(position, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        position: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPE

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        position: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val observedEntity = level.getBlockEntity(position) as? HoloBattleTerminalBlockEntity
            ?: return reject(serverPlayer, "terminal_missing")
        val playerPosition = serverPlayer.position()
        val currentEntity = level.getBlockEntity(position) as? HoloBattleTerminalBlockEntity
        val verification = HoloBattleTerminalContent.interactions.verify(
            TerminalInteractionSnapshot(
                expectedTerminalId = observedEntity.terminalId,
                blockEntityTerminalId = currentEntity?.terminalId,
                terminalDimensionId = level.dimension().location().toString(),
                terminalX = position.x,
                terminalY = position.y,
                terminalZ = position.z,
                playerDimensionId = serverPlayer.level().dimension().location().toString(),
                playerX = playerPosition.x,
                playerY = playerPosition.y,
                playerZ = playerPosition.z,
                terminalBlockPresent = level.getBlockState(position).`is`(HoloBattleTerminalContent.block),
                permitted = serverPlayer.mayInteract(level, position),
            ),
        )
        return when (verification) {
            is TerminalInteractionResult.Verified -> {
                if (HoloBattleTerminalContent.open(serverPlayer, verification)) {
                    InteractionResult.SUCCESS
                } else {
                    reject(serverPlayer, "open_failed")
                }
            }
            is TerminalInteractionResult.Rejected ->
                reject(serverPlayer, verification.reason.name.lowercase())
        }
    }

    private fun reject(player: ServerPlayer, reason: String): InteractionResult {
        player.sendSystemMessage(Component.translatable("message.${MoreBattleContent.MOD_ID}.terminal.$reason"), true)
        return InteractionResult.CONSUME
    }

    private companion object {
        val CODEC: MapCodec<HoloBattleTerminalBlock> = simpleCodec(::HoloBattleTerminalBlock)
        val SHAPE: VoxelShape = Shapes.or(
            Block.box(1.0, 0.0, 1.0, 15.0, 3.0, 15.0),
            Block.box(5.0, 3.0, 5.0, 11.0, 10.0, 11.0),
        )
    }
}
