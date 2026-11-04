package jbro.cobblemon.partysidebar.client

import jbro.cobblemon.partysidebar.preset.StorageMenu
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Inventory

/**
 * Vanilla's chest window over the item storage, with a scroll bar in a small panel on its right like the creative
 * inventory's. The wheel over the window or a drag on the bar picks the first row shown; the server swaps the rows'
 * items (see [StorageMenu.clickMenuButton]).
 */
class StorageScreen(menu: StorageMenu, inventory: Inventory, title: Component) : ContainerScreen(menu, inventory, title) {
    private var row = 0
    private var dragging = false

    private fun panelX() = leftPos + imageWidth - 1
    private fun panelY() = topPos + ROWS_TOP - TRACK_INSET
    private fun trackX() = panelX() + TRACK_INSET
    private fun trackY() = topPos + ROWS_TOP
    /** The scroll bar's panel as x, y, width, height, for EMI to keep clear. */
    internal fun scrollPanel() = intArrayOf(panelX(), panelY(), THUMB_WIDTH + TRACK_INSET * 2, TRACK_HEIGHT + TRACK_INSET * 2)

    private fun overTrack(mouseX: Double, mouseY: Double) =
        mouseX >= trackX() && mouseX < trackX() + THUMB_WIDTH && mouseY >= trackY() && mouseY < trackY() + TRACK_HEIGHT

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        super.renderBg(graphics, partialTick, mouseX, mouseY)
        val (x, y, width, height) = scrollPanel()
        InventorySkin.panel(graphics, x, y, width, height)
        graphics.fill(trackX(), trackY(), trackX() + THUMB_WIDTH, trackY() + TRACK_HEIGHT, TRACK)
        val thumbY = trackY() + (TRACK_HEIGHT - THUMB_HEIGHT) * row / StorageMenu.MAX_ROW
        graphics.blitSprite(SCROLLER, trackX(), thumbY, THUMB_WIDTH, THUMB_HEIGHT)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        scrollTo(row - Mth.sign(scrollY))
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && overTrack(mouseX, mouseY)) {
            dragging = true
            scrollTo(rowAt(mouseY))
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (dragging) {
            scrollTo(rowAt(mouseY))
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dragging && button == 0) {
            dragging = false
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    /** The bar sits outside the chest frame; a click there must not count as throwing the cursor item away. */
    override fun hasClickedOutside(mouseX: Double, mouseY: Double, left: Int, top: Int, button: Int): Boolean =
        !overTrack(mouseX, mouseY) && super.hasClickedOutside(mouseX, mouseY, left, top, button)

    private fun rowAt(mouseY: Double): Int {
        val position = (mouseY - trackY() - THUMB_HEIGHT / 2.0) / (TRACK_HEIGHT - THUMB_HEIGHT)
        return Math.round(position * StorageMenu.MAX_ROW).toInt()
    }

    private fun scrollTo(target: Int) {
        val next = target.coerceIn(0, StorageMenu.MAX_ROW)
        if (next == row) return
        row = next
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, next)
    }

    companion object {
        private val SCROLLER = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller")
        private const val ROWS_TOP = 18
        private const val TRACK_INSET = 4
        private const val THUMB_WIDTH = 12
        private const val THUMB_HEIGHT = 15
        private const val TRACK_HEIGHT = StorageMenu.VISIBLE_ROWS * 18
        private const val TRACK = 0xFF8B8B8B.toInt()
    }
}
