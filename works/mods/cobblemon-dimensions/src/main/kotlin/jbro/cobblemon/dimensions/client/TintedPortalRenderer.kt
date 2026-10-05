package jbro.cobblemon.dimensions.client

import com.mojang.blaze3d.vertex.PoseStack
import jbro.cobblemon.dimensions.ModDimension
import jbro.cobblemon.dimensions.portal.ParadoxPortalBlock
import jbro.cobblemon.dimensions.portal.ParadoxPortalBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer

/** The end portal's starfield, then a glowing wash of the portal's color over it. */
class TintedPortalRenderer(context: BlockEntityRendererProvider.Context) : TheEndPortalRenderer<ParadoxPortalBlockEntity>(context) {
    override fun render(portal: ParadoxPortalBlockEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int, overlay: Int) {
        super.render(portal, partialTick, pose, buffers, light, overlay)
        val block = portal.blockState.block as? ParadoxPortalBlock ?: return
        val color = COLORS[block.dimension] ?: return
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val y = offsetUp + 0.001f
        val matrix = pose.last().pose()
        // Lightning blends additively, so the color glows over the stars instead of covering them.
        val consumer = buffers.getBuffer(RenderType.lightning())
        consumer.addVertex(matrix, 0f, y, 1f).setColor(r, g, b, ALPHA)
        consumer.addVertex(matrix, 1f, y, 1f).setColor(r, g, b, ALPHA)
        consumer.addVertex(matrix, 1f, y, 0f).setColor(r, g, b, ALPHA)
        consumer.addVertex(matrix, 0f, y, 0f).setColor(r, g, b, ALPHA)
    }

    companion object {
        private const val ALPHA = 0.55f

        /** Ancient glows orange-red like Koraidon; future glows violet-cyan like Miraidon. */
        private val COLORS = mapOf(
            ModDimension.ANCIENT to 0xE8562A,
            ModDimension.FUTURE to 0x7A4CFF,
        )
    }
}
