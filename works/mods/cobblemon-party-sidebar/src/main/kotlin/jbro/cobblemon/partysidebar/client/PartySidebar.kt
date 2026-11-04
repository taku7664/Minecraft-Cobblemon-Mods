package jbro.cobblemon.partysidebar.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import com.mojang.blaze3d.systems.RenderSystem
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import jbro.cobblemon.partysidebar.network.ItemizePayload
import jbro.cobblemon.partysidebar.network.PresetActionPayload
import jbro.cobblemon.partysidebar.network.RestorePayload
import jbro.cobblemon.partysidebar.preset.PresetData
import jbro.cobblemon.partysidebar.preset.StorageMenu
import jbro.cobblemon.partysidebar.preset.StoredMark
import jbro.cobblemon.toitem.api.PokemonItemData
import jbro.cobblemon.toitem.api.PokemonTransfers
import jbro.cobblemon.toitem.api.client.PokemonTooltip
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
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
 * [HeldItemClickPayload]); without the mod on the server the column only shows the party. Ordinary Pokemon slot
 * clicks immediately convert the Pokemon to a cursor item. Shift-clicking moves
 * directly to the inventory. Placing a Pokemon item in a frame restores it there, exchanging an occupant onto the
 * cursor ([RestorePayload]).
 *
 * Under the rows sit four small buttons: fusion (Mega Showdown's DNA Splicers; opens PokeFusion's window as
 * `/pokefusion` does), add a preset, delete the current preset (click twice), and the item storage. Below them
 * `< 1 / 2 >` switches presets. A held-item slot whose preset lends a storage item the Pokemon is not holding is red.
 * The column also stands beside the item storage window, where storage items on the cursor can be lent to Pokemon.
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
    private const val BUTTON = 11
    private const val BUTTON_SPACING = 1
    private const val PAGER = 9
    private const val BOTTOM_INSET = 4
    private const val ICON = 8
    private const val DELETE_CONFIRM_MILLIS = 3000L
    private const val FUSION_COMMAND = "pokefusion"
    private val FUSION_ICON = ResourceLocation.fromNamespaceAndPath("mega_showdown", "dna_splicer")
    private val BUTTON_SPRITE = ResourceLocation.withDefaultNamespace("widget/button")
    private val BUTTON_HOVERED = ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    private val BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled")
    private val EMPTY_HELD_ITEM = CobblemonPartySidebar.id("textures/gui/empty_held_item.png")
    private val ICONS = CobblemonPartySidebar.id("textures/gui/sidebar_icons.png")

    // Vanilla's recipe book: its width, how far it shifts left of the centre, and the screen width below which it
    // covers the inventory instead of sitting beside it.
    private const val RECIPE_BOOK_WIDTH = 147
    private const val RECIPE_BOOK_OFFSET = 86
    private const val RECIPE_BOOK_NARROW = 379

    internal enum class Column { MODEL, HELD }

    internal data class Hit(val row: Int, val column: Column)

    /** The buttons at the column's foot; [icon] is the cell in `sidebar_icons.png`, -1 for the fusion item. */
    internal enum class Button(val icon: Int) { FUSION(-1), ADD(0), DELETE(1), STORAGE(2), PREVIOUS(3), NEXT(4) }

    internal data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height

        fun rowY(row: Int) = y + PADDING + row * FRAME

        /** The top-left corner of a cell's frame: the model frame fills the row, the slot sits in its middle. */
        fun cellX(column: Column) = x + PADDING + if (column == Column.MODEL) 0 else FRAME + COLUMN_GAP

        fun cellY(row: Int, column: Column) = rowY(row) + if (column == Column.MODEL) 0 else (FRAME - SLOT) / 2

        fun cellSize(column: Column) = if (column == Column.MODEL) FRAME else SLOT

        private fun barWidth() = 4 * BUTTON + 3 * BUTTON_SPACING
        fun barX() = x + (width - barWidth()) / 2
        fun pagerY() = y + height - BOTTOM_INSET - PAGER
        private fun buttonsY() = pagerY() - 1 - BUTTON

        /** x, y and size of a button: four in a row, the pager arrows at both ends of the row below. */
        fun rect(button: Button): Triple<Int, Int, Int> = when (button) {
            Button.PREVIOUS -> Triple(barX(), pagerY(), PAGER)
            Button.NEXT -> Triple(barX() + barWidth() - PAGER, pagerY(), PAGER)
            else -> Triple(barX() + button.ordinal * (BUTTON + BUTTON_SPACING), buttonsY(), BUTTON)
        }

        fun button(mouseX: Double, mouseY: Double): Button? = Button.entries.firstOrNull {
            val (bx, by, size) = rect(it)
            mouseX >= bx && mouseX < bx + size && mouseY >= by && mouseY < by + size
        }

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
        /** A held item taken onto the empty cursor; releasing it over an inventory slot puts it there. */
        data object HeldPickup : Press

        /** A press on the inventory itself; releasing it over a held-item slot puts the cursor item there. */
        data object Inventory : Press
    }

    private var press: Press? = null
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var deleteArmedUntil = 0L
    private val modelStacks = HashMap<UUID, Pair<String, ItemStack>>()

    /** Where the column goes on [screen], or null when it is off or there is no room for it. */
    internal fun bounds(screen: Screen): Bounds? {
        if (!SidebarConfig.enabled || screen !is AbstractContainerScreen<*>) return null
        if (!PokemonTransfers.supportsSidebarMenu(screen.menu) && screen.menu !is StorageMenu) return null
        var anchor = screen.leftPos
        if (screen is InventoryScreen && screen.getRecipeBookComponent().isVisible) {
            if (screen.width < RECIPE_BOOK_NARROW) return null
            anchor = minOf(anchor, (screen.width - RECIPE_BOOK_WIDTH) / 2 - RECIPE_BOOK_OFFSET)
        }
        val x = anchor - WIDTH
        if (x < 0) return null
        return Bounds(x, screen.topPos, WIDTH, maxOf(screen.imageHeight, InventorySkin.current().panelHeight,
            PADDING + ROWS_HEIGHT + 2 + BUTTON + 1 + PAGER + BOTTOM_INSET))
    }

    fun contains(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double) = bounds(screen)?.contains(mouseX, mouseY) == true

    fun renderColumn(screen: AbstractContainerScreen<*>, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        this.mouseX = mouseX.toDouble()
        this.mouseY = mouseY.toDouble()
        val bounds = bounds(screen) ?: return
        InventorySkin.panel(graphics, bounds.x, bounds.y, bounds.width, bounds.height)
        val hovered = bounds.hit(this.mouseX, this.mouseY)
        for (row in 0 until ROWS) {
            val pokemon = party(row)
            for (column in Column.entries) {
                val x = bounds.cellX(column)
                val y = bounds.cellY(row, column)
                val size = bounds.cellSize(column)
                when (column) {
                    Column.MODEL -> {
                        InventorySkin.box(graphics, x, y, size)
                        if (pokemon != null) drawModel(graphics, pokemon, x + (size - MODEL_SIZE) / 2, y + (size - MODEL_SIZE) / 2)
                    }
                    Column.HELD -> {
                        InventorySkin.slot(graphics, x, y)
                        if (pokemon != null && !PresetClient.missing(row).isEmpty) graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, MISSING)
                        val held = pokemon?.heldItem() ?: ItemStack.EMPTY
                        if (!held.isEmpty) {
                            graphics.renderItem(held, x + 1, y + 1)
                            graphics.renderItemDecorations(Minecraft.getInstance().font, held, x + 1, y + 1)
                        } else if (pokemon != null) {
                            drawEmptyHeldItem(graphics, x + 1, y + 1)
                        }
                    }
                }
                val highlight = hovered == Hit(row, column)
                if (highlight) graphics.fill(RenderType.guiOverlay(), x + 1, y + 1, x + size - 1, y + size - 1, HIGHLIGHT)
            }
        }
        drawBar(graphics, bounds, screen)
    }

    private fun enabled(button: Button, screen: AbstractContainerScreen<*>): Boolean {
        val state = PresetClient.state
        val presets = state != null && ClientPlayNetworking.canSend(PresetActionPayload.TYPE) &&
            (screen is InventoryScreen || screen.menu is StorageMenu)
        val calm = CobblemonClient.battle == null && screen.menu.carried.isEmpty
        return when (button) {
            Button.FUSION -> screen is InventoryScreen && serverHas(FUSION_COMMAND)
            Button.ADD -> presets && calm && state.count < PresetData.MAX_PRESETS
            Button.DELETE -> presets && calm && state.count > 1
            Button.STORAGE -> presets && screen is InventoryScreen && screen.menu.carried.isEmpty
            Button.PREVIOUS -> presets && calm && state.active > 0
            Button.NEXT -> presets && calm && state.active < state.count - 1
        }
    }

    /** The four buttons and the pager, from the active resource pack's button sprites. */
    private fun drawBar(graphics: GuiGraphics, bounds: Bounds, screen: AbstractContainerScreen<*>) {
        val armed = Util.getMillis() < deleteArmedUntil
        for (button in Button.entries) {
            val (x, y, size) = bounds.rect(button)
            val enabled = enabled(button, screen)
            val hovered = enabled && bounds.button(mouseX, mouseY) == button
            graphics.blitSprite(if (!enabled) BUTTON_DISABLED else if (hovered) BUTTON_HOVERED else BUTTON_SPRITE, x, y, size, size)
            if (button == Button.DELETE && armed && enabled) graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, ARMED)
            val offset = (size - ICON) / 2f
            if (button.icon >= 0) {
                RenderSystem.enableBlend()
                if (!enabled) graphics.setColor(0.55f, 0.55f, 0.55f, 1f)
                graphics.pose().pushPose()
                graphics.pose().translate(x + offset, y + offset, 0f)
                graphics.blit(ICONS, 0, 0, button.icon * ICON.toFloat(), 0f, ICON, ICON, ICON * 5, ICON)
                graphics.pose().popPose()
                graphics.setColor(1f, 1f, 1f, 1f)
                RenderSystem.disableBlend()
                continue
            }
            val icon = fusionIcon()
            graphics.pose().pushPose()
            graphics.pose().translate(x + offset, y + offset, 0f)
            if (icon != null) {
                graphics.pose().scale(ICON / 16f, ICON / 16f, 1f)
                graphics.renderItem(icon, 0, 0)
            } else {
                val font = Minecraft.getInstance().font
                val label = fusionLabel().string.take(1)
                graphics.drawString(font, label, (ICON - font.width(label)) / 2, 0, 0xFFFFFF, true)
            }
            graphics.pose().popPose()
            if (!enabled) graphics.fill(RenderType.guiOverlay(), x + 1, y + 1, x + size - 1, y + size - 1, DISABLED)
        }
        val state = PresetClient.state ?: return
        val font = Minecraft.getInstance().font
        val label = "${state.active + 1} / ${state.count}"
        val scale = 0.75f
        graphics.pose().pushPose()
        graphics.pose().translate(bounds.x + bounds.width / 2f, bounds.pagerY() + (PAGER - font.lineHeight * scale) / 2f + scale, 0f)
        graphics.pose().scale(scale, scale, 1f)
        graphics.drawString(font, label, -font.width(label) / 2, 0, InventorySkin.labelColor(), false)
        graphics.pose().popPose()
    }

    fun renderOverlay(screen: AbstractContainerScreen<*>, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val bounds = bounds(screen) ?: return
        if (!screen.menu.carried.isEmpty) return
        val font = Minecraft.getInstance().font
        bounds.button(mouseX.toDouble(), mouseY.toDouble())?.let { button ->
            graphics.renderTooltip(font, buttonLabel(button), mouseX, mouseY)
            return
        }
        val hit = bounds.hit(mouseX.toDouble(), mouseY.toDouble()) ?: return
        val pokemon = party(hit.row) ?: return
        when (hit.column) {
            Column.MODEL -> if (SidebarConfig.showTooltip) PokemonTooltip.render(graphics, pokemon, mouseX, mouseY)
            Column.HELD -> {
                val held = pokemon.heldItem()
                if (!held.isEmpty) graphics.renderTooltip(font, held, mouseX, mouseY)
                else PresetClient.missing(hit.row).takeUnless { it.isEmpty }?.let {
                    graphics.renderTooltip(font, Component.translatable("tooltip.cobblemon_party_sidebar.missing", it.hoverName), mouseX, mouseY)
                }
            }
        }
    }

    private fun buttonLabel(button: Button): Component = when (button) {
        Button.FUSION -> fusionLabel()
        Button.DELETE -> if (Util.getMillis() < deleteArmedUntil) Component.translatable("button.cobblemon_party_sidebar.delete_confirm")
            else Component.translatable("button.cobblemon_party_sidebar.delete")
        else -> Component.translatable("button.cobblemon_party_sidebar.${button.name.lowercase()}")
    }

    /** Returns true when the column took the click, so the inventory does not see it. */
    fun mouseClicked(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int): Boolean {
        press = null
        val bounds = bounds(screen) ?: return false
        if (!bounds.contains(mouseX, mouseY)) {
            press = Press.Inventory
            return false
        }
        bounds.button(mouseX, mouseY)?.let { pressed ->
            if (button == 0 && enabled(pressed, screen)) clickButton(pressed)
            return true
        }
        val hit = bounds.hit(mouseX, mouseY) ?: return true
        val pokemon = party(hit.row)
        when (hit.column) {
            // The storage window has no place for a Pokemon item; Cobblemon To Item refuses it there too.
            Column.MODEL -> if ((button == 0 || button == 1) && screen.menu !is StorageMenu) {
                if (!screen.menu.carried.isEmpty) restore(screen, hit.row)
                else if (pokemon != null) itemize(screen, hit.row, toCursor = !Screen.hasShiftDown())
            }
            Column.HELD -> {
                if (pokemon == null) return true
                if (button != 0 && button != 1) return true
                // A lent item goes back to the storage, so there is nothing to drag.
                val held = pokemon.heldItem()
                val pickup = screen.menu.carried.isEmpty && !held.isEmpty && !StoredMark.isStored(held)
                val quickMove = Screen.hasShiftDown()
                if (clickHeld(screen, hit.row, pokemon, quickMove) && pickup && !quickMove) press = Press.HeldPickup
            }
        }
        return true
    }

    private fun clickButton(button: Button) {
        val state = PresetClient.state
        when (button) {
            Button.FUSION -> Minecraft.getInstance().player?.connection?.sendCommand(FUSION_COMMAND)
            Button.ADD -> ClientPlayNetworking.send(PresetActionPayload(PresetActionPayload.Action.ADD, 0))
            Button.DELETE -> {
                if (Util.getMillis() < deleteArmedUntil) {
                    deleteArmedUntil = 0L
                    ClientPlayNetworking.send(PresetActionPayload(PresetActionPayload.Action.DELETE, 0))
                } else deleteArmedUntil = Util.getMillis() + DELETE_CONFIRM_MILLIS
            }
            Button.STORAGE -> ClientPlayNetworking.send(PresetActionPayload(PresetActionPayload.Action.OPEN_STORAGE, 0))
            Button.PREVIOUS -> state?.let { ClientPlayNetworking.send(PresetActionPayload(PresetActionPayload.Action.SELECT, it.active - 1)) }
            Button.NEXT -> state?.let { ClientPlayNetworking.send(PresetActionPayload(PresetActionPayload.Action.SELECT, it.active + 1)) }
        }
    }

    /** Returns true when the column finished the drag, so the inventory does not see the release. */
    fun mouseReleased(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int): Boolean {
        val started = press
        press = null
        val bounds = bounds(screen) ?: return false
        val over = bounds.contains(mouseX, mouseY)
        val hit = if (over) bounds.hit(mouseX, mouseY) else null
        when (started) {
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
                val target = hit ?: return true
                val pokemon = party(target.row)
                when (target.column) {
                    Column.MODEL -> if (screen.menu !is StorageMenu) restore(screen, target.row)
                    Column.HELD -> if (pokemon != null) clickHeld(screen, target.row, pokemon, quickMove = false)
                }
                return true
            }
            null -> return over
        }
    }

    /** Closing the item storage (Esc or the inventory key) goes back to the inventory rather than the game. */
    fun closed(screen: AbstractContainerScreen<*>) {
        if (screen.menu !is StorageMenu) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        minecraft.setScreen(InventoryScreen(player))
    }

    /** Asks the server to itemize the Pokemon in [row], placing the item on the cursor or directly in the inventory. */
    private fun itemize(screen: AbstractContainerScreen<*>, row: Int, toCursor: Boolean) {
        val pokemon = party(row) ?: return
        if (!canItemize() || !screen.menu.carried.isEmpty || CobblemonClient.battle != null) return
        val player = Minecraft.getInstance().player ?: return
        if (!toCursor && player.inventory.getFreeSlot() < 0) {
            player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.inventory_full"), true)
            return
        }
        ClientPlayNetworking.send(ItemizePayload(row, pokemon.uuid, toCursor))
    }

    /** Asks the server to put the itemized Pokemon on the cursor into party [row], exchanging any occupant onto the cursor. */
    private fun restore(screen: AbstractContainerScreen<*>, row: Int) {
        if (!canRestore() || !PokemonItemData.isPokemonItem(screen.menu.carried) || CobblemonClient.battle != null) return
        val pokemonTag = PokemonItemData.pokemonTag(screen.menu.carried) ?: return
        ClientPlayNetworking.send(RestorePayload(row, pokemonTag.getUUID("UUID"), party(row)?.uuid))
    }

    /** Asks the server to act on a held-item slot; false when there is nothing to do or no server half to ask. */
    private fun clickHeld(screen: AbstractContainerScreen<*>, row: Int, pokemon: Pokemon, quickMove: Boolean): Boolean {
        if (!ClientPlayNetworking.canSend(HeldItemClickPayload.TYPE)) return false
        if (pokemon.heldItem().isEmpty && (quickMove || screen.menu.carried.isEmpty)) return false
        ClientPlayNetworking.send(HeldItemClickPayload(row, pokemon.uuid, quickMove))
        return true
    }

    /** The server takes the request and has Cobblemon To Item's command, which it only sends to players allowed to use it. */
    internal fun canItemize(): Boolean =
        ClientPlayNetworking.canSend(ItemizePayload.TYPE) && serverHas(PokemonTransfers.COMMAND)

    private fun canRestore(): Boolean =
        ClientPlayNetworking.canSend(RestorePayload.TYPE) && serverHas(PokemonTransfers.COMMAND)

    private fun serverHas(command: String) = Minecraft.getInstance().connection?.commands?.root?.getChild(command) != null

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

    private fun fusionLabel(): Component = Component.translatableWithFallback("button.cobblemon_party_sidebar.fusion", "포켓몬 합성")

    private var fusionIconStack: ItemStack? = null

    /** Mega Showdown's DNA Splicers, the games' fusion tool, or null when that mod is not installed. */
    private fun fusionIcon(): ItemStack? {
        fusionIconStack?.let { return it.takeUnless(ItemStack::isEmpty) }
        val stack = BuiltInRegistries.ITEM.getOptional(FUSION_ICON).map { ItemStack(it) }.orElse(ItemStack.EMPTY)
        fusionIconStack = stack
        return stack.takeUnless(ItemStack::isEmpty)
    }

    private const val HIGHLIGHT = 0x80FFFFFF.toInt()
    private const val DISABLED = 0x90303030.toInt()
    private const val MISSING = 0x70D03030
    private const val ARMED = 0x80D03030.toInt()
}
