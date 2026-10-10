package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.NpcSkinRef
import com.mojang.blaze3d.platform.NativeImage
import java.security.MessageDigest
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Picks a trainer or NPC skin from the enabled resource packs, with a search box and a full-body preview.
 * Duplicate images within a namespace are listed once; tooltips show their complete texture IDs.
 */
class NpcSkinPickerScreen(private val parent: Screen, current: String, private val pick: (String) -> Unit) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.skin_picker")) {
    private val skins = availableSkins()
    private var query = ""
    private var offset = 0
    private var selected: String? = (NpcSkinRef.parse(current) as? NpcSkinRef.Texture)?.toString()
        ?.takeIf { it in skins }
    private val rows = mutableListOf<AbstractWidget>()
    private var preview: AbstractWidget? = null
    private var choose: AbstractWidget? = null
    private lateinit var search: EditBox

    override fun init() {
        super.init()
        panel(MARGIN, MARGIN, width - MARGIN * 2, height - MARGIN * 2, title)
        search = field(MARGIN + 10, MARGIN + 24, LIST_WIDTH, query, Component.translatable("screen.cobblemon_npc.hint.search"), 64)
        search.setResponder {
            query = it
            offset = 0
            refreshRows()
        }
        val actionsY = height - MARGIN - 30
        choose = button(width - MARGIN - 10 - 2 * ACTION_WIDTH - 6, actionsY, Component.translatable("screen.cobblemon_npc.pick"),
            UiButtonVariant.PRIMARY, ACTION_WIDTH) {
            selected?.let { pick(it) }
            onClose()
        }
        button(width - MARGIN - 10 - ACTION_WIDTH, actionsY, Component.translatable("gui.cancel"), UiButtonVariant.GHOST, ACTION_WIDTH) {
            onClose()
        }
        if (skins.isEmpty()) showResult(false, Component.translatable("screen.cobblemon_npc.skins_missing"))
        else label(Component.translatable("screen.cobblemon_npc.skin_count", skins.size), MARGIN + 10, actionsY + 6)
        refreshRows()
        setInitialFocus(search)
    }

    private fun filtered() = skins.filter { query.isBlank() || query.trim().lowercase() in it.lowercase() }

    private fun listTop() = MARGIN + 24 + FIELD_HEIGHT + 6

    private fun refreshRows() {
        rows.forEach(::removeWidget)
        rows.clear()
        val matches = filtered()
        val visible = ((height - MARGIN - 40 - listTop()) / ROW).coerceAtLeast(1)
        offset = offset.coerceIn(0, (matches.size - visible).coerceAtLeast(0))
        matches.drop(offset).take(visible).forEachIndexed { index, file ->
            val variant = if (file == selected) UiButtonVariant.PRIMARY else UiButtonVariant.GHOST
            rows += button(MARGIN + 10, listTop() + index * ROW, Component.literal(file.substringAfterLast('/').removeSuffix(".png")), variant, LIST_WIDTH) {
                selected = file
                refreshRows()
            }.also { it.setTooltip(Tooltip.create(Component.literal(file))) }
        }
        refreshPreview()
    }

    private fun refreshPreview() {
        preview?.let(::removeWidget)
        choose?.active = selected != null
        val file = selected ?: return
        val left = MARGIN + 10 + LIST_WIDTH + 16
        val top = MARGIN + 24
        val slotHeight = height - MARGIN - 40 - top
        val slotWidth = (slotHeight / 2).coerceAtMost(width - MARGIN - 10 - left)
        if (slotWidth <= 0 || slotHeight <= 0) return
        preview = addRenderableOnly(CobblemonUiRenderSlot.create(left, top, slotWidth, slotHeight,
            UiRenderSlotSpec(Component.literal(file.substringAfterLast('/').removeSuffix(".png"))), NpcSkins.content(file, UiModelFraming.FULL_BODY)))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseX < MARGIN + 10 + LIST_WIDTH && scrollY != 0.0) {
            offset -= scrollY.toInt().coerceIn(-1, 1) * 3
            refreshRows()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    companion object {
        private const val MARGIN = 8
        private const val LIST_WIDTH = 200
        private const val ROW = 21
        private const val ACTION_WIDTH = 80

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
