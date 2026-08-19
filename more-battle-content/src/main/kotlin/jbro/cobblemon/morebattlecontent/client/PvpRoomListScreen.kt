package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubContent
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSettings
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomDefaults
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomSummaryView
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

internal class PvpRoomListScreen(
    private val rooms: List<PvpRoomSummaryView>,
) : MbcTabbedContentScreen(Component.translatable(key("browser.title")), BattleHubContent.PVP) {
    private var page = 0
    private var feedbackKey: String? = null

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val frame = frameLayout()
        val layout = PvpRoomListLayout.calculate(frame.content)
        drawContentFrame(graphics, frame)
        MbcGuiSurface.drawPanel(graphics, layout.listPanel, MbcGuiPalette.ACCENT_PRIMARY)
        MbcGuiSurface.drawPanel(graphics, layout.footer, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        val summary = Component.translatable(key("browser.summary"), rooms.size)
        graphics.drawString(
            font,
            summary,
            layout.summaryRight - font.width(summary),
            layout.listPanel.top + 7,
            MbcGuiPalette.TEXT_SECONDARY,
            false,
        )
        if (rooms.isEmpty()) {
            graphics.drawCenteredString(
                font,
                Component.translatable(key("browser.empty")),
                width / 2,
                layout.listPanel.top + 36,
                MbcGuiPalette.TEXT_SECONDARY,
            )
        }
        feedbackKey?.let {
            graphics.drawCenteredString(font, Component.translatable(it), width / 2, layout.footer.top - 11, MbcGuiPalette.ACCENT_DANGER)
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    fun applyRejected(messageKey: String) {
        feedbackKey = messageKey
    }

    private fun buildWidgets() {
        val frame = frameLayout()
        val layout = PvpRoomListLayout.calculate(frame.content)
        addContentFrameWidgets(frame)
        addRenderableWidget(
            MbcStyledButton(
                layout.refreshButton,
                Component.translatable(key("browser.refresh")),
                MbcButtonTone.SECONDARY,
            ) {
                feedbackKey = null
                send(PvpRoomIntent.Refresh(UUID.randomUUID()))
            },
        )
        val pageSize = roomsPerPage(layout)
        val visible = rooms.drop(page * pageSize).take(pageSize)
        visible.forEachIndexed { index, room ->
            val bounds = TowerPlayRect(layout.listPanel.left + 6, layout.listPanel.top + 21 + index * 23, layout.listPanel.width - 12, 19)
            val phase = Component.translatable(key("phase.${room.phase.name.lowercase()}"))
            val format = Component.translatable(key("format.${room.settings.format.recordId}"))
            val visibility = Component.translatable(key("visibility.${room.settings.visibility.name.lowercase()}"))
            val label = Component.translatable(
                key("browser.room"),
                room.host.name,
                format,
                phase,
                room.spectatorCount,
                visibility,
            )
            addRenderableWidget(MbcStyledButton(bounds, label, MbcButtonTone.PRIMARY) {
                PvpPlayClientNetworking.openRoom(this, room.roomId)
            })
        }
        val actions = layout.actionButtons(4)
        addRenderableWidget(MbcStyledButton(actions[0], Component.translatable(key("browser.create_public")), MbcButtonTone.PRIMARY) {
            create(PvpRoomVisibility.PUBLIC)
        })
        addRenderableWidget(MbcStyledButton(actions[1], Component.translatable(key("browser.create_private")), MbcButtonTone.SECONDARY) {
            create(PvpRoomVisibility.PRIVATE)
        })
        addRenderableWidget(MbcStyledButton(actions[2], Component.translatable(key("browser.previous"))) {
            if (page > 0) {
                page--
                rebuild()
            }
        }.also { it.active = page > 0 })
        addRenderableWidget(MbcStyledButton(actions[3], Component.translatable(key("browser.next"))) {
            if ((page + 1) * pageSize < rooms.size) {
                page++
                rebuild()
            }
        }.also { it.active = (page + 1) * pageSize < rooms.size })
    }

    private fun create(visibility: PvpRoomVisibility) {
        send(
            PvpRoomIntent.Create(
                UUID.randomUUID(),
                PvpRoomSettings(visibility, PvpBattleFormat.SINGLE, PvpRoomDefaults.ENABLED_MECHANICS),
            ),
        )
    }

    private fun send(intent: PvpRoomIntent) = PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent))

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private fun roomsPerPage(layout: PvpRoomListLayout): Int = ((layout.listPanel.height - 24) / 23).coerceAtLeast(1)

    private companion object {
        fun key(path: String) = "screen.${MoreBattleContent.MOD_ID}.pvp.room.$path"
    }
}
