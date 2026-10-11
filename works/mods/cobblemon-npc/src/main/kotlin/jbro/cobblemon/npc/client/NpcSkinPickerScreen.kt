package jbro.cobblemon.npc.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import jbro.cobblemon.npc.content.NpcPokemonForms
import jbro.cobblemon.npc.dialogue.NpcSkinRef
import com.mojang.blaze3d.platform.NativeImage
import java.security.MessageDigest
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Picks a trainer or NPC skin from the enabled resource packs, or a Cobblemon Pokemon, on two tabs, from a vanilla
 * scrolling list with a search box and a full-body preview. Duplicate images within a namespace are listed once;
 * tooltips show complete IDs. A double click picks a row.
 */
class NpcSkinPickerScreen(private val parent: Screen, current: String, private val pick: (String) -> Unit) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.skin_picker")) {
    private enum class Tab(val key: String) { PEOPLE("screen.cobblemon_npc.tab.people"), POKEMON("screen.cobblemon_npc.tab.pokemon") }

    private val people = availableSkins()
    private val pokemon = availablePokemon()
    private val labels = (people + pokemon).associateWith(::labelOf)
    private var tab = if (NpcSkinRef.parse(current) is NpcSkinRef.Pokemon) Tab.POKEMON else Tab.PEOPLE
    private var query = ""
    private var selected: String? = NpcSkinRef.parse(current).takeIf { it is NpcSkinRef.Texture || it is NpcSkinRef.Pokemon }
        ?.toString()?.takeIf { it in labels }
    /** The chosen skin drawn beside the list: where, what, and its name. */
    private var preview: Triple<UiRect, CobblemonUiRenderContent, Component>? = null
    private var choose: AbstractWidget? = null
    private lateinit var list: SkinList

    override fun init() {
        super.init()
        status = null
        panel(MARGIN, MARGIN, width - MARGIN * 2, height - MARGIN * 2, title)
        val left = MARGIN + 10
        var y = MARGIN + 22
        val tabWidth = (LIST_WIDTH - 4) / 2
        Tab.entries.forEachIndexed { index, each ->
            button(left + index * (tabWidth + 4), y, Component.translatable(each.key),
                if (each == tab) UiButtonVariant.PRIMARY else UiButtonVariant.SECONDARY, tabWidth) {
                if (tab != each) {
                    tab = each
                    query = ""
                    rebuildWidgets()
                }
            }
        }
        y += BUTTON_HEIGHT + 4
        val search = field(left, y, LIST_WIDTH, query, Component.translatable("screen.cobblemon_npc.hint.search"), 64)
        y += FIELD_HEIGHT + 4
        val actionsY = height - MARGIN - 30
        list = addRenderableWidget(SkinList(LIST_WIDTH, (actionsY - 6 - y).coerceAtLeast(ITEM), y).also { it.x = left })
        search.setResponder {
            query = it
            refreshList()
        }
        choose = button(width - MARGIN - 10 - 2 * ACTION_WIDTH - 6, actionsY, Component.translatable("screen.cobblemon_npc.pick"),
            UiButtonVariant.PRIMARY, ACTION_WIDTH) { choose() }
        button(width - MARGIN - 10 - ACTION_WIDTH, actionsY, Component.translatable("gui.cancel"), UiButtonVariant.GHOST, ACTION_WIDTH) {
            onClose()
        }
        val shown = shown()
        if (shown.isEmpty()) showResult(false, Component.translatable("screen.cobblemon_npc.skins_missing"))
        else label(Component.translatable("screen.cobblemon_npc.skin_count", shown.size), left, actionsY + 6)
        refreshList()
        refreshPreview()
        setInitialFocus(search)
    }

    private fun shown() = if (tab == Tab.POKEMON) pokemon else people

    private fun refreshList() {
        val text = query.trim().lowercase()
        list.show(shown().filter { text.isEmpty() || text in it.lowercase() || text in labels.getValue(it).lowercase() })
    }

    private fun choose() {
        selected?.let { pick(it) }
        onClose()
    }

    private fun refreshPreview() {
        preview = null
        choose?.active = selected != null
        val file = selected ?: return
        val left = MARGIN + 10 + LIST_WIDTH + 16
        val top = MARGIN + 24
        val slotHeight = height - MARGIN - 40 - top
        val slotWidth = (slotHeight / 2).coerceAtMost(width - MARGIN - 10 - left)
        if (slotWidth <= 0 || slotHeight <= 0) return
        preview = Triple(UiRect(left, top, slotWidth, slotHeight - 12), NpcSkins.content(file, UiModelFraming.FULL_BODY),
            Component.literal(labels.getValue(file)))
    }

    // The model alone on the screen's background with its name under it, without a framed slot.
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val (bounds, content, name) = preview ?: return
        CobblemonUiRenderSlot.drawContent(graphics, bounds, content, partialTick)
        graphics.drawCenteredString(font, name, bounds.x + bounds.width / 2, bounds.bottom + 3, 0xFFFFFF)
    }

    /** Minecraft's own scrolling list, as the language and resource pack screens use, with its scrollbar. */
    private inner class SkinList(width: Int, height: Int, y: Int) :
        ObjectSelectionList<SkinEntry>(Minecraft.getInstance(), width, height, y, ITEM) {
        fun show(values: List<String>) {
            replaceEntries(values.map { SkinEntry(it) })
            val chosen = children().firstOrNull { it.value == this@NpcSkinPickerScreen.selected }
            super.setSelected(chosen)
            if (chosen != null) centerScrollOn(chosen) else setScrollAmount(0.0)
        }

        // The list has a `selected` of its own; the screen's chosen skin is named through the screen.
        override fun setSelected(entry: SkinEntry?) {
            super.setSelected(entry)
            if (entry != null && entry.value != this@NpcSkinPickerScreen.selected) {
                this@NpcSkinPickerScreen.selected = entry.value
                refreshPreview()
            }
        }

        override fun getRowWidth() = getWidth() - 16

        override fun getScrollbarPosition() = getX() + getWidth() - 6
    }

    private inner class SkinEntry(val value: String) : ObjectSelectionList.Entry<SkinEntry>() {
        private val label = Component.literal(labels.getValue(value))
        private var lastClick = 0L

        override fun getNarration(): Component = label

        override fun render(graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
                            mouseX: Int, mouseY: Int, hovering: Boolean, partialTick: Float) {
            val color = if (value == selected) 0xFFFF55 else 0xFFFFFF
            graphics.drawString(Minecraft.getInstance().font, label, left + 3, top + (height - 8) / 2, color)
            if (hovering) setTooltipForNextRenderPass(Component.literal(value))
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            val now = Util.getMillis()
            if (now - lastClick < DOUBLE_CLICK && value == selected) choose()
            lastClick = now
            return true
        }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    companion object {
        private const val MARGIN = 8
        private const val LIST_WIDTH = 200
        private const val ITEM = 16
        private const val ACTION_WIDTH = 80
        private const val DOUBLE_CLICK = 250L

        private fun labelOf(skin: String): String = when (val ref = NpcSkinRef.parse(skin)) {
            is NpcSkinRef.Pokemon -> NpcPokemonForms.form(ref)?.species?.translatedName?.string ?: ref.path
            else -> skin.substringAfterLast('/').removeSuffix(".png")
        }

        /** Every species with a Cobblemon model on this client, in Pokedex order. */
        fun availablePokemon(): List<String> = try {
            PokemonSpecies.implemented.sortedBy { it.nationalPokedexNumber }.map {
                NpcSkinRef.pokemon(it.resourceIdentifier.namespace, it.resourceIdentifier.path)
            }
        } catch (_: RuntimeException) {
            emptyList()
        }

        /** Discover player skins in trainer/NPC/skin directories across all enabled mods and packs. */
        fun availableSkins(): List<String> {
            val manager = Minecraft.getInstance().resourceManager
            val found = manager.listResources("textures") {
                it.path.endsWith(".png") && NpcSkinRef.isSkinFolder(it.path)
            }.toSortedMap(compareBy(ResourceLocation::toString))
            val seen = HashSet<String>()
            return found.mapNotNull { (location, resource) ->
                val bytes = try { resource.open().use { it.readAllBytes() } } catch (_: Exception) { return@mapNotNull null }
                val valid = try {
                    bytes.inputStream().use { stream -> NativeImage.read(stream).use {
                        it.width == 64 && (it.height == 64 || it.height == 32)
                    } }
                } catch (_: Exception) { false }
                if (!valid) return@mapNotNull null
                val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                // Keep each pack's names available even when another mod bundles the same image.
                if (!seen.add("${location.namespace}:$key")) null else location.toString()
            }
        }
    }
}
