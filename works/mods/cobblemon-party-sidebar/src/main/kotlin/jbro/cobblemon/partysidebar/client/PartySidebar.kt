package jbro.cobblemon.partysidebar.client

import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.net.messages.server.storage.party.MovePartyPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.party.SwapPartyPokemonPacket
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import org.joml.Vector4f
import java.util.UUID

/**
 * The party column left of the survival inventory: one row per party slot, the Pokemon model and then its held item.
 *
 * Held-item slots act like inventory slots: a click swaps the slot with the cursor item, shift-click sends the item to
 * the inventory, and an item can be dragged between them and the inventory. The server makes every change (see
 * [HeldItemClickPayload]); without the mod on the server the column only shows the party. Dragging a Pokemon onto
 * another row swaps the two, or moves it into an empty row, through Cobblemon's own party packets.
 */
object PartySidebar {
    private const val ROWS = 6
    private const val SLOT = 18
    private const val PADDING = 7
    private const val COLUMN_GAP = 2
    private const val ROW_GAP = 2
    private const val SCREEN_GAP = 2
    const val WIDTH = PADDING * 2 + SLOT * 2 + COLUMN_GAP
    const val HEIGHT = PADDING * 2 + SLOT * ROWS + ROW_GAP * (ROWS - 1)

    // Vanilla's recipe book: its width, how far it shifts left of the centre, and the screen width below which it
    // covers the inventory instead of sitting beside it.
    private const val RECIPE_BOOK_WIDTH = 147
    private const val RECIPE_BOOK_OFFSET = 86
    private const val RECIPE_BOOK_NARROW = 379

    internal enum class Column { MODEL, HELD }

    internal data class Hit(val row: Int, val column: Column)

    internal data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height

        fun slotX(column: Column) = x + PADDING + if (column == Column.MODEL) 0 else SLOT + COLUMN_GAP

        fun slotY(row: Int) = y + PADDING + row * (SLOT + ROW_GAP)

        fun hit(mouseX: Double, mouseY: Double): Hit? {
            for (row in 0 until ROWS) for (column in Column.entries) {
                val slotX = slotX(column)
                val slotY = slotY(row)
                if (mouseX >= slotX && mouseX < slotX + SLOT && mouseY >= slotY && mouseY < slotY + SLOT) return Hit(row, column)
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
    private val modelStacks = HashMap<UUID, Pair<String, ItemStack>>()

    /** Where the column goes on [screen], or null when it is off or there is no room for it. */
    internal fun bounds(screen: Screen): Bounds? {
        if (!SidebarConfig.enabled || screen !is InventoryScreen) return null
        var anchor = screen.leftPos
        if (screen.getRecipeBookComponent().isVisible) {
            if (screen.width < RECIPE_BOOK_NARROW) return null
            anchor = minOf(anchor, (screen.width - RECIPE_BOOK_WIDTH) / 2 - RECIPE_BOOK_OFFSET)
        }
        val x = anchor - SCREEN_GAP - WIDTH
        return if (x < 0) null else Bounds(x, screen.topPos, WIDTH, HEIGHT)
    }

    fun contains(screen: InventoryScreen, mouseX: Double, mouseY: Double) = bounds(screen)?.contains(mouseX, mouseY) == true

    fun renderColumn(screen: InventoryScreen, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val bounds = bounds(screen) ?: return
        drawPanel(graphics, bounds)
        val hovered = bounds.hit(mouseX.toDouble(), mouseY.toDouble())
        val dragged = (press as? Press.Model)?.row
        for (row in 0 until ROWS) {
            val pokemon = party(row)
            for (column in Column.entries) {
                val x = bounds.slotX(column)
                val y = bounds.slotY(row)
                drawSlot(graphics, x, y)
                if (pokemon != null) when (column) {
                    Column.MODEL -> if (row != dragged) graphics.renderItem(modelStack(pokemon), x + 1, y + 1)
                    Column.HELD -> {
                        val held = pokemon.heldItem()
                        if (!held.isEmpty) {
                            graphics.renderItem(held, x + 1, y + 1)
                            graphics.renderItemDecorations(Minecraft.getInstance().font, held, x + 1, y + 1)
                        }
                    }
                }
                val highlight = hovered == Hit(row, column) || (dragged != null && hovered?.row == row && row != dragged)
                if (highlight) AbstractContainerScreen.renderSlotHighlight(graphics, x + 1, y + 1, 0)
            }
        }
    }

    fun renderOverlay(screen: InventoryScreen, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val bounds = bounds(screen) ?: return
        (press as? Press.Model)?.let { drag ->
            val pokemon = party(drag.row) ?: return
            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 232f)
            graphics.renderItem(modelStack(pokemon), mouseX - 8, mouseY - 8)
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

    /** Asks the server to act on a held-item slot; false when there is nothing to do or no server half to ask. */
    private fun clickHeld(screen: InventoryScreen, row: Int, pokemon: Pokemon, quickMove: Boolean): Boolean {
        if (!ClientPlayNetworking.canSend(HeldItemClickPayload.TYPE)) return false
        if (pokemon.heldItem().isEmpty && (quickMove || screen.menu.carried.isEmpty)) return false
        ClientPlayNetworking.send(HeldItemClickPayload(row, pokemon.uuid, quickMove))
        return true
    }

    private fun reorder(from: Int, to: Int) {
        if (CobblemonClient.battle != null) return
        val moving = party(from) ?: return
        val target = party(to)
        if (target != null) SwapPartyPokemonPacket(moving.uuid, PartyPosition(from), target.uuid, PartyPosition(to)).sendToServer()
        else MovePartyPokemonPacket(moving.uuid, PartyPosition(from), PartyPosition(to)).sendToServer()
    }

    private fun party(row: Int): Pokemon? = CobblemonClient.storage.party.get(row)

    /** The model as Cobblemon's Pokemon item draws it, greyed out once the Pokemon has fainted. */
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

    /** A panel in the inventory's own style: light grey body, bevelled edge and black outline. */
    private fun drawPanel(graphics: GuiGraphics, bounds: Bounds) {
        val (x, y, w, h) = bounds
        val outline = 0xFF000000.toInt()
        graphics.fill(x + 1, y, x + w - 1, y + 1, outline)
        graphics.fill(x + 1, y + h - 1, x + w - 1, y + h, outline)
        graphics.fill(x, y + 1, x + 1, y + h - 1, outline)
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, outline)
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6.toInt())
        graphics.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFFFFFFF.toInt())
        graphics.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFFFFFFF.toInt())
        graphics.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, 0xFF555555.toInt())
        graphics.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, 0xFF555555.toInt())
    }

    /** An empty inventory slot: dark top-left edge, white bottom-right edge, grey inside. */
    private fun drawSlot(graphics: GuiGraphics, x: Int, y: Int) {
        graphics.fill(x, y, x + SLOT, y + SLOT, 0xFF8B8B8B.toInt())
        graphics.fill(x, y, x + SLOT - 1, y + 1, 0xFF373737.toInt())
        graphics.fill(x, y, x + 1, y + SLOT - 1, 0xFF373737.toInt())
        graphics.fill(x + 1, y + SLOT - 1, x + SLOT, y + SLOT, 0xFFFFFFFF.toInt())
        graphics.fill(x + SLOT - 1, y + 1, x + SLOT, y + SLOT, 0xFFFFFFFF.toInt())
    }
}
