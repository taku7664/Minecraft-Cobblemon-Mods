package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class PvpMemberPickerScreen(
    private val parent: Screen,
    title: Component,
    private val members: List<PvpRoomMemberView>,
    private val select: (PvpRoomMemberView) -> Unit,
) : MccScreen(title) {
    private var page = 0

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MccGuiSurface.drawBackdrop(graphics, width, height)
        MccGuiSurface.drawShell(graphics, shell())
        MccGuiSurface.drawPanel(graphics, header(), MccGuiPalette.ACCENT_SECONDARY, alternate = true)
        MccGuiSurface.drawPanel(graphics, listPanel(), MccGuiPalette.ACCENT_PRIMARY)
        graphics.drawCenteredString(font, title, width / 2, header().top + 9, MccGuiPalette.ACCENT_PRIMARY)
        if (members.isEmpty()) {
            graphics.drawCenteredString(
                font,
                Component.translatable(key("picker.empty")),
                width / 2,
                listPanel().top + 24,
                MccGuiPalette.TEXT_SECONDARY,
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
                MccStyledButton(
                    MccRect(listPanel().left + 7, listPanel().top + 7 + index * 23, listPanel().width - 14, 19),
                    Component.literal(member.name),
                    MccButtonTone.PRIMARY,
                ) {
                    minecraft?.setScreen(parent)
                    select(member)
                },
            )
        }
        val buttons = split(footer(), 3)
        addRenderableWidget(MccStyledButton(buttons[0], Component.translatable(key("browser.previous"))) {
            page--
            rebuild()
        }.also { it.active = page > 0 })
        addRenderableWidget(MccStyledButton(buttons[1], Component.translatable(key("browser.next"))) {
            page++
            rebuild()
        }.also { it.active = (page + 1) * PAGE_SIZE < members.size })
        addRenderableWidget(MccStyledButton(buttons[2], Component.translatable("gui.back")) { onClose() })
    }

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private fun shell(): MccRect {
        val w = (width - 24).coerceAtMost(330)
        val h = (height - 24).coerceAtMost(240)
        return MccRect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun header() = MccRect(shell().left + 6, shell().top + 6, shell().width - 12, 28)
    private fun listPanel() = MccRect(shell().left + 6, header().bottom + 5, shell().width - 12, shell().height - 82)
    private fun footer() = MccRect(shell().left + 6, shell().bottom - 32, shell().width - 12, 20)

    private fun split(bounds: MccRect, count: Int): List<MccRect> {
        val gap = 4
        val itemWidth = (bounds.width - gap * (count - 1)) / count
        return List(count) { index -> MccRect(bounds.left + index * (itemWidth + gap), bounds.top, itemWidth, bounds.height) }
    }

    private companion object {
        const val PAGE_SIZE = 6
        fun key(path: String) = "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.$path"
    }
}
