package jbro.cobblemon.partysidebar.client

import com.mojang.blaze3d.platform.NativeImage
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

/**
 * Draws the party column out of the inventory texture of whatever resource pack is active, so the column wears the
 * same frame, background, slots and player-model box as the inventory beside it.
 *
 * Packs keep vanilla's layout but move edges by a pixel or two, so the panel size and the player-model box are
 * measured from the texture after each resource reload; a texture that cannot be read falls back to vanilla's numbers.
 */
internal object InventorySkin {
    val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png")
    private const val SIZE = 256
    private const val EDGE = 4
    private const val BOX_EDGE = 2
    private const val SIDE_V = 62
    private const val SIDE_HEIGHT = 16

    // Vanilla places an armor slot at (7, 7); its frame is the plain slot the held-item column copies.
    private const val SLOT_U = 7
    private const val SLOT_V = 7
    const val SLOT = 18

    data class Rect(val u: Int, val v: Int, val width: Int, val height: Int)

    /** The measured texture: panel size, the panel's body colour (ARGB) and the player-model box. */
    data class Skin(val panelWidth: Int, val panelHeight: Int, val bodyColor: Int, val box: Rect)

    private val VANILLA = Skin(176, 166, 0xFFC6C6C6.toInt(), Rect(25, 7, 51, 72))
    private var skin: Skin? = null

    fun current(): Skin = skin ?: measure().also { skin = it }

    fun invalidate() {
        skin = null
    }

    /** A panel in the inventory's frame: its corners and edges, filled with its body colour. */
    fun panel(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        val skin = current()
        val right = skin.panelWidth - EDGE
        val bottom = skin.panelHeight - EDGE
        graphics.fill(x + EDGE, y + EDGE, x + width - EDGE, y + height - EDGE, skin.bodyColor)
        // The side edges come from the strip between the crafting result and the inventory rows: higher up, packs
        // draw the result slot into the right edge, and lower down they often notch the edge around the rows.
        tiled(graphics, x + EDGE, y, width - EDGE * 2, EDGE, 8, 0, 32, EDGE)
        tiled(graphics, x + EDGE, y + height - EDGE, width - EDGE * 2, EDGE, 8, bottom, 32, EDGE)
        tiled(graphics, x, y + EDGE, EDGE, height - EDGE * 2, 0, SIDE_V, EDGE, SIDE_HEIGHT)
        tiled(graphics, x + width - EDGE, y + EDGE, EDGE, height - EDGE * 2, right, SIDE_V, EDGE, SIDE_HEIGHT)
        blit(graphics, x, y, 0, 0, EDGE, EDGE)
        blit(graphics, x + width - EDGE, y, right, 0, EDGE, EDGE)
        blit(graphics, x, y + height - EDGE, 0, bottom, EDGE, EDGE)
        blit(graphics, x + width - EDGE, y + height - EDGE, right, bottom, EDGE, EDGE)
    }

    /** An inventory slot frame, 18 by 18. */
    fun slot(graphics: GuiGraphics, x: Int, y: Int) = blit(graphics, x, y, SLOT_U, SLOT_V, SLOT, SLOT)

    /** A frame like the player-model box, stretched to [size]. */
    fun box(graphics: GuiGraphics, x: Int, y: Int, size: Int) {
        val (u, v, w, h) = current().box
        val b = BOX_EDGE
        tiled(graphics, x + b, y + b, size - b * 2, size - b * 2, u + b, v + b, w - b * 2, h - b * 2)
        tiled(graphics, x + b, y, size - b * 2, b, u + b, v, w - b * 2, b)
        tiled(graphics, x + b, y + size - b, size - b * 2, b, u + b, v + h - b, w - b * 2, b)
        tiled(graphics, x, y + b, b, size - b * 2, u, v + b, b, h - b * 2)
        tiled(graphics, x + size - b, y + b, b, size - b * 2, u + w - b, v + b, b, h - b * 2)
        blit(graphics, x, y, u, v, b, b)
        blit(graphics, x + size - b, y, u + w - b, v, b, b)
        blit(graphics, x, y + size - b, u, v + h - b, b, b)
        blit(graphics, x + size - b, y + size - b, u + w - b, v + h - b, b, b)
    }

    private fun blit(graphics: GuiGraphics, x: Int, y: Int, u: Int, v: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) graphics.blit(TEXTURE, x, y, u.toFloat(), v.toFloat(), width, height, SIZE, SIZE)
    }

    /** Fills a [width] by [height] area by repeating the texture piece at ([u], [v]). */
    private fun tiled(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, u: Int, v: Int, pieceWidth: Int, pieceHeight: Int) {
        if (pieceWidth <= 0 || pieceHeight <= 0) return
        var dy = 0
        while (dy < height) {
            val h = minOf(pieceHeight, height - dy)
            var dx = 0
            while (dx < width) {
                val w = minOf(pieceWidth, width - dx)
                blit(graphics, x + dx, y + dy, u, v, w, h)
                dx += w
            }
            dy += h
        }
    }

    private fun measure(): Skin = try {
        val resource = Minecraft.getInstance().resourceManager.getResource(TEXTURE).orElse(null)
        resource?.open()?.use { NativeImage.read(it) }?.use { measure(it) } ?: VANILLA
    } catch (failure: Exception) {
        CobblemonPartySidebar.LOGGER.warn("Could not read the inventory texture; using vanilla's layout", failure)
        VANILLA
    }

    /**
     * The panel ends at the last opaque pixel on a row and a column through its top section. The player-model box is
     * the run of its inside colour around (50, 40), plus the border pixel on each side.
     */
    private fun measure(image: NativeImage): Skin? {
        if (image.width != SIZE || image.height != SIZE) return null
        fun pixel(x: Int, y: Int) = image.getPixelRGBA(x, y)
        fun opaque(x: Int, y: Int) = pixel(x, y) ushr 24 != 0
        val width = (SIZE - 1 downTo 0).firstOrNull { opaque(it, 40) }?.plus(1) ?: return null
        val height = (SIZE - 1 downTo 0).firstOrNull { opaque(40, it) }?.plus(1) ?: return null
        if (width < 100 || height < 100) return null
        val inside = pixel(50, 40)
        var left = 50
        while (left > 0 && pixel(left, 40) == inside) left--
        var right = 50
        while (right < width - 1 && pixel(right, 40) == inside) right++
        var top = 40
        while (top > 0 && pixel(50, top) == inside) top--
        var bottom = 40
        while (bottom < height - 1 && pixel(50, bottom) == inside) bottom++
        val box = Rect(left, top, right - left + 1, bottom - top + 1)
        if (box.width < BOX_EDGE * 2 + 2 || box.height < BOX_EDGE * 2 + 2) return null
        return Skin(width, height, argb(pixel(width - 10, 68)), box)
    }

    /** [NativeImage] pixels are ABGR; fills want ARGB. */
    private fun argb(abgr: Int): Int =
        (abgr and 0xFF00FF00.toInt()) or ((abgr and 0xFF) shl 16) or ((abgr shr 16) and 0xFF)
}
