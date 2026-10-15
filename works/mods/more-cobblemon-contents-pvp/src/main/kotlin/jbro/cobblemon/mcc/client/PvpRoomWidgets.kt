package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/** A floating card naming who is watching, drawn over everything else at the mouse. */
internal object PvpSpectatorCard {
    private const val PADDING = 4
    private const val ROW = 11
    private const val BIG_FACE = 32

    /** One spectator: a large face and the name under it. */
    fun drawOne(graphics: GuiGraphics, mouseX: Int, mouseY: Int, member: PvpRoomMemberView) {
        val font = Minecraft.getInstance().font
        val width = maxOf(BIG_FACE, font.width(member.name)) + PADDING * 2
        val height = PADDING + BIG_FACE + 3 + font.lineHeight + PADDING
        draw(graphics, mouseX, mouseY, width, height) { x, y ->
            face(graphics, member, x + (width - BIG_FACE) / 2, y + PADDING, BIG_FACE)
            graphics.drawString(font, member.name, x + (width - font.width(member.name)) / 2, y + PADDING + BIG_FACE + 3, TEXT, false)
        }
    }

    /** Everyone watching, a small face before each name, under [heading]. */
    fun drawList(graphics: GuiGraphics, mouseX: Int, mouseY: Int, heading: Component, members: List<PvpRoomMemberView>, more: Component?) {
        val font = Minecraft.getInstance().font
        val width = (listOf(font.width(heading), more?.let(font::width) ?: 0) + members.map { font.width(it.name) + 11 }).max() + PADDING * 2
        val height = PADDING * 2 + ROW * (1 + members.size + if (more != null) 1 else 0)
        draw(graphics, mouseX, mouseY, width, height) { x, y ->
            var line = y + PADDING
            graphics.drawString(font, heading, x + PADDING, line + 1, DIM, false)
            members.forEach { member ->
                line += ROW
                face(graphics, member, x + PADDING, line, 8)
                graphics.drawString(font, member.name, x + PADDING + 11, line + 1, TEXT, false)
            }
            more?.let { graphics.drawString(font, it, x + PADDING, line + ROW + 1, DIM, false) }
        }
    }

    fun face(graphics: GuiGraphics, member: PvpRoomMemberView, x: Int, y: Int, size: Int) {
        val profile = Minecraft.getInstance().connection?.getPlayerInfo(member.playerId)?.profile ?: return
        CobblemonUiRenderSlot.drawContent(graphics, UiRect(x, y, size, size), CobblemonUiRenderContent.PlayerFace(profile), 0f)
    }

    private inline fun draw(graphics: GuiGraphics, mouseX: Int, mouseY: Int, width: Int, height: Int, content: (Int, Int) -> Unit) {
        val x = (mouseX + 10).let { if (it + width > graphics.guiWidth() - 2) mouseX - 10 - width else it }.coerceAtLeast(2)
        val y = (mouseY - 6).coerceIn(2, (graphics.guiHeight() - height - 2).coerceAtLeast(2))
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 400f)
        graphics.fill(x, y, x + width, y + height, BACKGROUND)
        graphics.renderOutline(x, y, width, height, BORDER)
        content(x, y)
        graphics.pose().popPose()
    }

    private const val BACKGROUND = 0xF0100010.toInt()
    private const val BORDER = 0xFF5028A0.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val DIM = 0xFFAAAAAA.toInt()
}
