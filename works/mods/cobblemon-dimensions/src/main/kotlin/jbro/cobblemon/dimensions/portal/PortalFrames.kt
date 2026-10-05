package jbro.cobblemon.dimensions.portal

import jbro.cobblemon.dimensions.CobblemonDimensions
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Portal frames are laid out like an end portal: twelve blocks around a 3x3 hole, corners left out. Which blocks count
 * is a block tag per portal (`tags/block/<ancient|future>_portal_frame.json`), so swapping a frame block is a data edit.
 */
object PortalFrames {
    val ANCIENT_FRAME: TagKey<Block> = TagKey.create(Registries.BLOCK, CobblemonDimensions.id("ancient_portal_frame"))
    val FUTURE_FRAME: TagKey<Block> = TagKey.create(Registries.BLOCK, CobblemonDimensions.id("future_portal_frame"))

    /** The twelve frame offsets from the hole's center. */
    private val RING: List<Pair<Int, Int>> = (-1..1).flatMap { k -> listOf(-2 to k, 2 to k, k to -2, k to 2) }

    fun isFrame(state: BlockState): Boolean = state.`is`(ANCIENT_FRAME) || state.`is`(FUTURE_FRAME)

    fun frameTag(state: BlockState): TagKey<Block>? = when {
        state.`is`(ANCIENT_FRAME) -> ANCIENT_FRAME
        state.`is`(FUTURE_FRAME) -> FUTURE_FRAME
        else -> null
    }

    /**
     * Lights the portal whose frame includes [clicked], when the whole ring is that frame and the hole is free. Returns
     * whether one was lit.
     */
    fun tryLight(level: Level, clicked: BlockPos): Boolean {
        val tag = frameTag(level.getBlockState(clicked)) ?: return false
        val portal = if (tag == ANCIENT_FRAME) PortalBlocks.ANCIENT_PORTAL else PortalBlocks.FUTURE_PORTAL
        for ((ox, oz) in RING) {
            val center = clicked.offset(-ox, 0, -oz)
            val ringOk = RING.all { (x, z) -> level.getBlockState(center.offset(x, 0, z)).`is`(tag) }
            val hole = (-1..1).flatMap { x -> (-1..1).map { z -> center.offset(x, 0, z) } }
            if (!ringOk || !hole.all { level.getBlockState(it).canBeReplaced() }) continue
            for (pos in hole) level.setBlock(pos, portal.defaultBlockState(), Block.UPDATE_CLIENTS)
            level.playSound(null, center, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1f, 1f)
            return true
        }
        return false
    }
}
