package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.NpcSkinRef
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Picks an RCT Trainers+ trainer skin from the enabled resource packs, with a search box and a full-body preview.
 * The pack ships many files that are copies of one image; each image is listed once, under its first name.
 */
class RctSkinPickerScreen(private val parent: Screen, current: String, private val pick: (String) -> Unit) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.rct_picker")) {
    private val skins = rctSkins()
    private var query = ""
    private var offset = 0
    private var selected: String? = (NpcSkinRef.parse(current) as? NpcSkinRef.Texture)
        ?.takeIf { it.namespace == NpcSkinRef.RCT_NAMESPACE }?.path?.substringAfterLast('/')?.removeSuffix(".png")
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
            selected?.let { pick(NpcSkinRef.rct(it)) }
            onClose()
        }
        button(width - MARGIN - 10 - ACTION_WIDTH, actionsY, Component.translatable("gui.cancel"), UiButtonVariant.GHOST, ACTION_WIDTH) {
            onClose()
        }
        if (skins.isEmpty()) showResult(false, Component.translatable("screen.cobblemon_npc.rct_missing"))
        else label(Component.translatable("screen.cobblemon_npc.rct_count", skins.size), MARGIN + 10, actionsY + 6)
        refreshRows()
        setInitialFocus(search)
    }

    private fun filtered() = skins.filter { query.isBlank() || query.trim().lowercase() in it }

    private fun listTop() = MARGIN + 24 + FIELD_HEIGHT + 6

    private fun refreshRows() {
        rows.forEach(::removeWidget)
        rows.clear()
        val matches = filtered()
        val visible = ((height - MARGIN - 40 - listTop()) / ROW).coerceAtLeast(1)
        offset = offset.coerceIn(0, (matches.size - visible).coerceAtLeast(0))
        matches.drop(offset).take(visible).forEachIndexed { index, file ->
            val variant = if (file == selected) UiButtonVariant.PRIMARY else UiButtonVariant.GHOST
            rows += button(MARGIN + 10, listTop() + index * ROW, Component.literal(file), variant, LIST_WIDTH) {
                selected = file
                refreshRows()
            }
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
            UiRenderSlotSpec(Component.literal(file)), NpcSkins.content(NpcSkinRef.rct(file), UiModelFraming.FULL_BODY)))
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

        /** The trainer skins the enabled packs carry, one name per distinct image, sorted. */
        fun rctSkins(): List<String> {
            val manager = Minecraft.getInstance().resourceManager
            val found = manager.listResources(NpcSkinRef.RCT_FOLDER) { it.path.endsWith(".png") }
                .filterKeys { it.namespace == NpcSkinRef.RCT_NAMESPACE }
                .toSortedMap(compareBy(ResourceLocation::getPath))
            val seen = HashSet<String>()
            return found.mapNotNull { (location, resource) ->
                val bytes = try {
                    resource.open().use { it.readAllBytes() }
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val key = "${bytes.size}:${bytes.contentHashCode()}"
                if (!seen.add(key)) null else location.path.substringAfterLast('/').removeSuffix(".png")
            }
        }
    }
}
