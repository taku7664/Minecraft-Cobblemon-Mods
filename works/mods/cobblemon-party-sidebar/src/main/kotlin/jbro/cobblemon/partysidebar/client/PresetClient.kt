package jbro.cobblemon.partysidebar.client

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.PresetStatePayload
import jbro.cobblemon.partysidebar.preset.StoredMark
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack

/**
 * The presets as the server last reported them, and how storage items look: a small chest in the corner of the item
 * and before its name. Null until a server with the preset half sends its first report.
 */
object PresetClient {
    private val ICONS = CobblemonPartySidebar.id("textures/gui/sidebar_icons.png")
    private val GLYPH_FONT = CobblemonPartySidebar.id("storage_icon")
    private const val BADGE_U = 16f
    private const val BADGE_SIZE = 6

    /** The private-use character `font/storage_icon.json` maps to the chest. */
    private val GLYPH = Character.toString(0xE000)

    var state: PresetStatePayload? = null

    /** The storage item [row]'s preset lends but its Pokemon is not holding, or EMPTY. */
    fun missing(row: Int): ItemStack = state?.missing?.getOrNull(row) ?: ItemStack.EMPTY

    /** Whether [row]'s preset names a Pokemon that is not standing in that row. */
    fun missingPokemon(row: Int): Boolean = state?.missingPokemon?.getOrNull(row) == true

    /**
     * The chest glyph from `font/storage_icon.json` and a space, put before a storage item's name. The root has no
     * style: appended parts inherit their parent's, and the name drawn in the glyph font came out as boxes.
     */
    fun namePrefix(): MutableComponent = Component.empty()
        .append(Component.literal(GLYPH).withStyle(Style.EMPTY.withFont(GLYPH_FONT).withColor(ChatFormatting.WHITE)))
        .append(Component.literal(" "))

    /** Drawn over the item's top-left corner, above its count and durability bar. */
    @JvmStatic fun renderBadge(graphics: GuiGraphics, stack: ItemStack, x: Int, y: Int) {
        if (!StoredMark.isStored(stack)) return
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 200f)
        graphics.blit(ICONS, x, y, BADGE_SIZE, BADGE_SIZE, BADGE_U, 0f, 8, 8, 48, 8)
        graphics.pose().popPose()
    }
}
