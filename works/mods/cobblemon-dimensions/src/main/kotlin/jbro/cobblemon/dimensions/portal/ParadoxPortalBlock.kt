package jbro.cobblemon.dimensions.portal

import com.mojang.serialization.MapCodec
import jbro.cobblemon.dimensions.DimensionTravel
import jbro.cobblemon.dimensions.ModDimension
import jbro.cobblemon.dimensions.ModDimensions
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The lit surface of an ancient or future portal: an end portal in its frame, leading to [dimension]. Stepping in from
 * outside these dimensions enters [dimension]; stepping in from inside any of them goes back to the entry point.
 */
class ParadoxPortalBlock(val dimension: ModDimension, properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = simpleCodec { ParadoxPortalBlock(dimension, it) }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ParadoxPortalBlockEntity(pos, state)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    /** A broken frame puts the portal out; each portal block that loses its neighbor goes, so the whole surface goes. */
    override fun updateShape(state: BlockState, direction: Direction, neighbor: BlockState, level: LevelAccessor,
                             pos: BlockPos, neighborPos: BlockPos): BlockState {
        if (direction.axis.isHorizontal && neighbor.block !is ParadoxPortalBlock && !PortalFrames.isFrame(neighbor)) {
            return Blocks.AIR.defaultBlockState()
        }
        return state
    }

    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (level.isClientSide || entity !is ServerPlayer || entity.isOnPortalCooldown) return
        entity.setPortalCooldown()
        // Teleport after this tick's collision pass, not in the middle of it.
        val inside = ModDimensions.isOurs(level)
        entity.server.execute {
            if (inside) {
                DimensionTravel.returnHome(entity)
            } else if (!DimensionTravel.enter(entity, dimension, pos.x, pos.z)) {
                entity.displayClientMessage(net.minecraft.network.chat.Component.translatable("command.cobblemon_dimensions.no_landing"), true)
            }
        }
    }

    companion object {
        private val SHAPE: VoxelShape = box(0.0, 6.0, 0.0, 16.0, 12.0, 16.0)
    }
}
