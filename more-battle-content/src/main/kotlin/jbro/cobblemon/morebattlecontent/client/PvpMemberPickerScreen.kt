package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomMemberView
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class PvpMemberPickerScreen(
    private val parent: Screen,
    title: Component,
    private val members: List<PvpRoomMemberView>,
    private val select: (PvpRoomMemberView) -> Unit,
) : MbcScreen(title) {
    private var page = 0

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MbcGuiSurface.drawBackdrop(graphics, width, height)
        MbcGuiSurface.drawShell(graphics, shell())
        MbcGuiSurface.drawPanel(graphics, header(), MbcGuiPalette.ACCENT_SECONDARY, alternate = true)
        MbcGuiSurface.drawPanel(graphics, listPanel(), MbcGuiPalette.ACCENT_PRIMARY)
        graphics.drawCenteredString(font, title, width / 2, header().top + 9, MbcGuiPalette.ACCENT_PRIMARY)
        if (members.isEmpty()) {
            graphics.drawCenteredString(
                font,
                Component.translatable(key("picker.empty")),
                width / 2,
                listPanel().top + 24,
                MbcGuiPalette.TEXT_SECONDARY,
            )
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    private fun buildWidgets() {
        members.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, member ->
            addRenderableWidget(
                MbcStyledButton(
                    TowerPlayRect(listPanel().left + 7, listPanel().top + 7 + index * 23, listPanel().width - 14, 19),
                    Component.literal(member.name),
                    MbcButtonTone.PRIMARY,
                ) { select(member) },
            )
        }
        val buttons = split(footer(), 3)
        addRenderableWidget(MbcStyledButton(buttons[0], Component.translatable(key("browser.previous"))) {
            page--
            rebuild()
        }.also { it.active = page > 0 })
        addRenderableWidget(MbcStyledButton(buttons[1], Component.translatable(key("browser.next"))) {
            page++
            rebuild()
        }.also { it.active = (page + 1) * PAGE_SIZE < members.size })
        addRenderableWidget(MbcStyledButton(buttons[2], Component.translatable("gui.back")) { onClose() })
    }

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private fun shell(): TowerPlayRect {
        val w = (width - 24).coerceAtMost(330)
        val h = (height - 24).coerceAtMost(240)
        return TowerPlayRect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun header() = TowerPlayRect(shell().left + 6, shell().top + 6, shell().width - 12, 28)
    private fun listPanel() = TowerPlayRect(shell().left + 6, header().bottom + 5, shell().width - 12, shell().height - 82)
    private fun footer() = TowerPlayRect(shell().left + 6, shell().bottom - 32, shell().width - 12, 20)

    private fun split(bounds: TowerPlayRect, count: Int): List<TowerPlayRect> {
        val gap = 4
        val itemWidth = (bounds.width - gap * (count - 1)) / count
        return List(count) { index -> TowerPlayRect(bounds.left + index * (itemWidth + gap), bounds.top, itemWidth, bounds.height) }
    }

    private companion object {
        const val PAGE_SIZE = 6
        fun key(path: String) = "screen.${MoreBattleContent.MOD_ID}.pvp.room.$path"
    }
}
