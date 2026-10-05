package jbro.cobblemon.dimensions.portal

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity
import net.minecraft.world.level.block.state.BlockState

/** The end portal's block entity, so the end portal renderer draws its starfield; the client tints it per portal. */
class ParadoxPortalBlockEntity(pos: BlockPos, state: BlockState) : TheEndPortalBlockEntity(PortalBlocks.PORTAL_ENTITY, pos, state)
