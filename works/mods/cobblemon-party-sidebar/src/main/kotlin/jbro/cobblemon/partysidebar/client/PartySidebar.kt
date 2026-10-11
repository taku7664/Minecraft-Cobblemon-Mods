package jbro.cobblemon.partysidebar.client

import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.net.messages.server.storage.party.MovePartyPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.party.SwapPartyPokemonPacket
import com.cobblemon.mod.common.pokemon.Pokemon
import com.mojang.blaze3d.systems.RenderSystem
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import jbro.cobblemon.partysidebar.network.ItemizePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import org.joml.Vector4f
import java.util.UUID

/**
 * The party column docked to the left of the survival inventory: one row per party slot, the Pokemon in a frame like
 * the player-model box and then its held item in an inventory slot. Both are cut from the active inventory texture
 * (see [InventorySkin]), so the column matches the inventory under any resource pack.
 *
 * Held-item slots act like inventory slots: a click swaps the slot with the cursor item, shift-click sends the item to
 * the inventory, and an item can be dragged between them and the inventory. The server makes every change (see
 * [HeldItemClickPayload]); without the mod on the server the column only shows the party. Dragging a Pokemon onto
 * another row swaps the two, or moves it into an empty row, through Cobblemon's own party packets. The itemize key
 * over a Pokemon turns it into a PokemonToItem item ([ItemizePayload]); the Pokemon tooltip names the key.
 */
object PartySidebar {
    private const val ROWS = 6
    private const val FRAME = 22
    private const val SLOT = InventorySkin.SLOT
    private const val PADDING = 7
    private const val COLUMN_GAP = 2
    const val WIDTH = PADDING * 2 + FRAME + COLUMN_GAP + SLOT
    private const val ROWS_HEIGHT = FRAME * ROWS
    private const val MODEL_SIZE = 20
    private val EMPTY_HELD_ITEM = CobblemonPartySidebar.id("textures/gui/empty_held_item.png")

    // Vanilla's recipe book: its width, how far it shifts left of the centre, and the screen width below which it
    // covers the inventory instead of sitting beside it.
    private const val RECIPE_BOOK_WIDTH = 147
    private const val RECIPE_BOOK_OFFSET = 86
    private const val RECIPE_BOOK_NARROW = 379

    internal enum class Column { MODEL, HELD }

    internal data class Hit(val row: Int, val column: Column)

    internal data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height

        fun rowY(row: Int) = y + PADDING + row * FRAME

        /** The top-left corner of a cell's frame: the model frame fills the row, the slot sits in its middle. */
        fun cellX(column: Column) = x + PADDING + if (column == Column.MODEL) 0 else FRAME + COLUMN_GAP

        fun cellY(row: Int, column: Column) = rowY(row) + if (column == Column.MODEL) 0 else (FRAME - SLOT) / 2

        fun cellSize(column: Column) = if (column == Column.MODEL) FRAME else SLOT

        fun hit(mouseX: Double, mouseY: Double): Hit? {
            for (row in 0 until ROWS) for (column in Column.entries) {
                val cellX = cellX(column)
                val cellY = cellY(row, column)
                val size = cellSize(column)
                if (mouseX >= cellX && mouseX < cellX + size && mouseY >= cellY && mouseY < cellY + size) return Hit(row, column)
            }
            return null
        }
    }

    /** What the current mouse press started on, so the release can finish a drag. */
    private sealed interface Press {
        /** A Pokemon picked up to reorder the party. */
        data class Model(val row: Int) : Press

        /** A held item taken onto the empty cursor; releasing it over an inventory slot puts it there. */
        data object HeldPickup : Press

        /** A press on the inventory itself; releasing it over a held-item slot puts the cursor item there. */
        data object Inventory : Press
    }

    private var press: Press? = null
    private var mouseX = 0.0
    private var mouseY = 0.0
    private val modelStacks = HashMap<UUID, Pair<String, ItemStack>>()

    /** Where the column goes on [screen], or null when it is off or there is no room for it. */
    internal fun bounds(screen: Screen): Bounds? {
        if (!SidebarConfig.enabled || screen !is InventoryScreen) return null
        var anchor = screen.leftPos
        if (screen.getRecipeBookComponent().isVisible) {
            if (screen.width < RECIPE_BOOK_NARROW) return null
            anchor = minOf(anchor, (screen.width - RECIPE_BOOK_WIDTH) / 2 - RECIPE_BOOK_OFFSET)
        }
        val x = anchor - WIDTH
        if (x < 0) return null
        return Bounds(x, screen.topPos, WIDTH, maxOf(InventorySkin.current().panelHeight, PADDING * 2 + ROWS_HEIGHT))
    }

    fun contains(screen: InventoryScreen, mouseX: Double, mouseY: Double) = bounds(screen)?.contains(mouseX, mouseY) == true

    fun renderColumn(screen: InventoryScreen, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        this.mouseX = mouseX.toDouble()
        this.mouseY = mouseY.toDouble()
        val bounds = bounds(screen) ?: return
        InventorySkin.panel(graphics, bounds.x, bounds.y, bounds.width, bounds.height)
        val hovered = bounds.hit(this.mouseX, this.mouseY)
        val dragged = (press as? Press.Model)?.row
        for (row in 0 until ROWS) {
            val pokemon = party(row)
            for (column in Column.entries) {
                val x = bounds.cellX(column)
                val y = bounds.cellY(row, column)
                val size = bounds.cellSize(column)
                when (column) {
                    Column.MODEL -> {
                        InventorySkin.box(graphics, x, y, size)
                        if (pokemon != null && row != dragged) drawModel(graphics, pokemon, x + (size - MODEL_SIZE) / 2, y + (size - MODEL_SIZE) / 2)
                    }
                    Column.HELD -> {
                        InventorySkin.slot(graphics, x, y)
                        val held = pokemon?.heldItem() ?: ItemStack.EMPTY
                        if (!held.isEmpty) {
                            graphics.renderItem(held, x + 1, y + 1)
                            graphics.renderItemDecorations(Minecraft.getInstance().font, held, x + 1, y + 1)
                        } else if (pokemon != null) {
                            drawEmptyHeldItem(graphics, x + 1, y + 1)
                        }
                    }
                }
                val highlight = hovered == Hit(row, column) || (dragged != null && hovered?.row == row && row != dragged)
                if (highlight) graphics.fill(RenderType.guiOverlay(), x + 1, y + 1, x + size - 1, y + size - 1, HIGHLIGHT)
            }
        }
    }

    fun renderOverlay(screen: InventoryScreen, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val bounds = bounds(screen) ?: return
        (press as? Press.Model)?.let { drag ->
            val pokemon = party(drag.row) ?: return
            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 232f)
            drawModel(graphics, pokemon, mouseX - MODEL_SIZE / 2, mouseY - MODEL_SIZE / 2)
            graphics.pose().popPose()
            return
        }
        if (!screen.menu.carried.isEmpty) return
        val hit = bounds.hit(mouseX.toDouble(), mouseY.toDouble()) ?: return
        val pokemon = party(hit.row) ?: return
        when (hit.column) {
            Column.MODEL -> if (SidebarConfig.showTooltip) PokemonTooltip.render(graphics, pokemon, mouseX, mouseY)
            Column.HELD -> pokemon.heldItem().takeUnless { it.isEmpty }?.let {
                graphics.renderTooltip(Minecraft.getInstance().font, it, mouseX, mouseY)
            }
        }
    }

    /** Returns true when the column took the click, so the inventory does not see it. */
    fun mouseClicked(screen: InventoryScreen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        press = null
        val bounds = bounds(screen) ?: return false
        if (!bounds.contains(mouseX, mouseY)) {
            press = Press.Inventory
            return false
        }
        val hit = bounds.hit(mouseX, mouseY) ?: return true
        val pokemon = party(hit.row) ?: return true
        when (hit.column) {
            Column.MODEL -> if (button == 0 && CobblemonClient.battle == null) press = Press.Model(hit.row)
            Column.HELD -> {
                if (button != 0 && button != 1) return true
                val pickup = screen.menu.carried.isEmpty && !pokemon.heldItem().isEmpty
                val quickMove = Screen.hasShiftDown()
                if (clickHeld(screen, hit.row, pokemon, quickMove) && pickup && !quickMove) press = Press.HeldPickup
            }
        }
        return true
    }

    /** Returns true when the column finished the drag, so the inventory does not see the release. */
    fun mouseReleased(screen: InventoryScreen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        val started = press
        press = null
        val bounds = bounds(screen) ?: return false
        val over = bounds.contains(mouseX, mouseY)
        val hit = if (over) bounds.hit(mouseX, mouseY) else null
        when (started) {
            is Press.Model -> {
                if (hit != null && hit.row != started.row) reorder(started.row, hit.row)
                return true
            }
            Press.HeldPickup -> {
                if (!over) screen.hoveredSlot?.let { slot ->
                    val minecraft = Minecraft.getInstance()
                    val player = minecraft.player ?: return true
                    minecraft.gameMode?.handleInventoryMouseClick(screen.menu.containerId, slot.index, button, ClickType.PICKUP, player)
                }
                return true
            }
            Press.Inventory -> {
                if (!over) return false
                // The press may have started vanilla's drag-split; a release on the column ends it here instead.
                screen.isQuickCrafting = false
                val target = hit?.takeIf { it.column == Column.HELD } ?: return true
                val pokemon = party(target.row) ?: return true
                clickHeld(screen, target.row, pokemon, quickMove = false)
                return true
            }
            null -> return over
        }
    }

    /** The itemize key over a Pokemon itemizes it; returns true when the key was used. */
    fun keyPressed(screen: InventoryScreen, keyCode: Int, scanCode: Int): Boolean {
        if (!CobblemonPartySidebarClient.ITEMIZE.matches(keyCode, scanCode) || !canItemize()) return false
        val bounds = bounds(screen) ?: return false
        val hit = bounds.hit(mouseX, mouseY)?.takeIf { it.column == Column.MODEL } ?: return false
        val pokemon = party(hit.row) ?: return false
        if (press != null || !screen.menu.carried.isEmpty) return true
        ClientPlayNetworking.send(ItemizePayload(hit.row, pokemon.uuid))
        return true
    }

    /** Asks the server to act on a held-item slot; false when there is nothing to do or no server half to ask. */
    private fun clickHeld(screen: InventoryScreen, row: Int, pokemon: Pokemon, quickMove: Boolean): Boolean {
        if (!ClientPlayNetworking.canSend(HeldItemClickPayload.TYPE)) return false
        if (pokemon.heldItem().isEmpty && (quickMove || screen.menu.carried.isEmpty)) return false
        ClientPlayNetworking.send(HeldItemClickPayload(row, pokemon.uuid, quickMove))
        return true
    }

    /** The server takes the request and has PokemonToItem's command, which it only sends to players allowed to use it. */
    internal fun canItemize(): Boolean =
        ClientPlayNetworking.canSend(ItemizePayload.TYPE) &&
            Minecraft.getInstance().connection?.commands?.root?.getChild("poketoitem") != null

    private fun reorder(from: Int, to: Int) {
        if (CobblemonClient.battle != null) return
        val moving = party(from) ?: return
        val target = party(to)
        if (target != null) SwapPartyPokemonPacket(moving.uuid, PartyPosition(from), target.uuid, PartyPosition(to)).sendToServer()
        else MovePartyPokemonPacket(moving.uuid, PartyPosition(from), PartyPosition(to)).sendToServer()
    }

    private fun party(row: Int): Pokemon? = CobblemonClient.storage.party.get(row)

    /** The model as Cobblemon's Pokemon item draws it, [MODEL_SIZE] across, greyed out once the Pokemon has fainted. */
    private fun drawModel(graphics: GuiGraphics, pokemon: Pokemon, x: Int, y: Int) {
        graphics.pose().pushPose()
        graphics.pose().translate(x.toFloat(), y.toFloat(), 0f)
        graphics.pose().scale(MODEL_SIZE / 16f, MODEL_SIZE / 16f, 1f)
        graphics.renderItem(modelStack(pokemon), 0, 0)
        graphics.pose().popPose()
    }

    private fun modelStack(pokemon: Pokemon): ItemStack {
        val fainted = pokemon.currentHealth <= 0
        val key = "${pokemon.species.resourceIdentifier}|${pokemon.aspects.sorted()}|$fainted"
        modelStacks[pokemon.uuid]?.let { (cachedKey, stack) -> if (cachedKey == key) return stack }
        val tint = if (fainted) Vector4f(0.45f, 0.45f, 0.45f, 1f) else null
        val stack = PokemonItem.from(pokemon.species, pokemon.aspects, 1, tint)
        if (modelStacks.size > 64) modelStacks.clear()
        modelStacks[pokemon.uuid] = key to stack
        return stack
    }

    /** A faint berry outline, like the armor slots' empty icons, marking where a held item goes. */
    private fun drawEmptyHeldItem(graphics: GuiGraphics, x: Int, y: Int) {
        RenderSystem.enableBlend()
        graphics.setColor(1f, 1f, 1f, 0.28f)
        graphics.blit(EMPTY_HELD_ITEM, x, y, 0f, 0f, 16, 16, 16, 16)
        graphics.setColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
    }

    private const val HIGHLIGHT = 0x80FFFFFF.toInt()
}
