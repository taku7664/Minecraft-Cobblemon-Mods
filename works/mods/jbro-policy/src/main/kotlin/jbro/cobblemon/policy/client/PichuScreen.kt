package jbro.cobblemon.policy.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import jbro.cobblemon.policy.menu.RequestLinkPayload
import jbro.cobblemon.policy.plaza.Plaza
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.client.CobblemonUiThemedButton
import jbro.cobblemon.uikit.client.UiDialogueStyle
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import kotlin.math.sin

/**
 * `/피츄`: Pichu's help screen. The world blurs behind it as under the pause menu; Pichu stands in the left 30% and
 * follows the cursor with its head, as the summary's portrait does; the shortcuts sit in two columns, centred in the
 * rest; and the message box below speaks for Pichu: what a hovered button does, otherwise a remark on the player's
 * state or one of its own. The buttons and the box are in the content theme, as every message box is.
 */
class PichuScreen : Screen(Component.literal("피츄")) {
    private class Entry(val label: String, val hint: String, val available: Boolean, val action: () -> Unit)

    private val buttons = mutableListOf<Pair<CobblemonUiThemedButton, Entry>>()
    private var model: ModelWidget? = null
    private var shown = ""
    private var revealStart = System.nanoTime()
    private var idleLine = ""
    private var idleSince = 0L

    override fun init() {
        buttons.clear()
        val left = leftWidth()
        val species = PokemonSpecies.getByName("pichu")
        model = species?.let {
            val size = minOf(left - 16, height - 40).coerceAtLeast(40)
            // The summary draws a 66-wide portrait at scale 2, offset −10; this keeps those proportions.
            val scale = size / 66f
            ModelWidget(
                pX = (left - size) / 2, pY = (height - size) / 2 - 10, pWidth = size, pHeight = size,
                pokemon = RenderablePokemon(it, emptySet()),
                baseScale = 2f * scale, rotationY = 325f, offsetY = -10.0 * scale,
                playCryOnClick = true, shouldFollowCursor = true,
            ).also(::addRenderableWidget)
        }

        val entries = entries()
        val box = boxRect()
        val areaLeft = left + MARGIN
        val areaWidth = width - areaLeft - MARGIN
        val columnWidth = ((areaWidth - COLUMN_GAP) / 2).coerceAtMost(BUTTON_MAX_WIDTH)
        val rows = (entries.size + 1) / 2
        val gridWidth = columnWidth * 2 + COLUMN_GAP
        val gridHeight = rows * BUTTON_HEIGHT + (rows - 1) * ROW_GAP
        val gridLeft = areaLeft + (areaWidth - gridWidth) / 2
        val gridTop = ((box.y - gridHeight) / 2).coerceAtLeast(MARGIN)
        entries.forEachIndexed { index, entry ->
            val column = index % 2
            val row = index / 2
            val bounds = UiRect(gridLeft + column * (columnWidth + COLUMN_GAP), gridTop + row * (BUTTON_HEIGHT + ROW_GAP),
                columnWidth, BUTTON_HEIGHT)
            val button = CobblemonUiThemedButton(bounds, Component.literal(entry.label), UiButtonVariant.PRIMARY) { entry.action() }
            button.theme = UiDialogueStyle::theme
            button.active = entry.available
            buttons += addRenderableWidget(button) to entry
        }
    }

    override fun isPauseScreen() = false

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Pichu keeps watching the cursor wherever it goes, not only while it hovers the portrait.
        model?.lookStartTime = System.currentTimeMillis()
        super.render(graphics, mouseX, mouseY, partialTick)
        val hovered = buttons.firstOrNull { (button, _) -> button.isHovered }?.second
        val text = when {
            hovered == null -> idle()
            hovered.available -> hovered.hint
            else -> UNAVAILABLE
        }
        if (text != shown) {
            shown = text
            revealStart = System.nanoTime()
        }
        drawBox(graphics)
    }

    /** One remark at a time while nothing is hovered, a new one a while after the last finished showing. */
    private fun idle(): String {
        val now = System.currentTimeMillis()
        if (idleLine.isEmpty() || now - idleSince > IDLE_MILLIS) {
            val pool = PichuLines.idle(minecraft!!).filter { it != idleLine }
            idleLine = pool.randomOrNull() ?: idleLine
            idleSince = now
        }
        return idleLine
    }

    private fun entries(): List<Entry> {
        val client = minecraft!!
        val level = client.level
        val inPlaza = level?.dimension() == Plaza.DIMENSION
        val inRoom = level?.dimension()?.location()?.toString() == MYROOM_DIMENSION
        val roomRoot = listOf("myroom", "room").firstOrNull(::serverHas)
        val policy = ClientPlayNetworking.canSend(RequestLinkPayload.TYPE)
        return listOf(
            if (inPlaza) Entry("광장 나가기", "광장에서 나가면 들어오기 전에 있던 자리로 돌아가요. 다시 모험하러 가요!", serverHas("plaza")) { command("plaza exit") }
            else Entry("광장 가기", "광장에 가면 간호순 누나가 포켓몬을 회복해 주고, 상점이랑 리그·배틀타워·배틀팩토리 안내원도 다 모여 있어요!", serverHas("plaza")) { command("plaza enter") },
            if (inRoom) Entry("마이룸 나가기", "방에서 나가면 들어오기 전에 있던 자리로 돌아가요.", roomRoot != null) { command("$roomRoot exit") }
            else Entry("마이룸 가기", "나만 쓰는 방이에요! 마음대로 꾸미고 친구를 초대할 수도 있어요. 처음 가면 방부터 만들어 줘요.", roomRoot != null) { command("$roomRoot enter") },
            Entry("BP 상점", "모은 BP로 사탕이랑 민트, 배틀 도구를 살 수 있어요. 인벤토리 빈칸은 꼭 챙겨요!", serverHas("mcc")) { hub(SHOP_TAB) },
            Entry("PvP 룸", "다른 트레이너랑 겨루는 곳이에요. 방을 만들거나 열린 방에 들어가 봐요!", serverHas("mcc")) { hub(PVP_TAB) },
            Entry("문의하기", "버그나 건의는 운영자님께 바로 전할 수 있어요. 무슨 일인지 내용까지 같이 써 줘야 해요!", serverHas("문의")) {
                client.setScreen(ChatScreen("/문의 "))
            },
            Entry("구역 스폰 레벨", "지금 서 있는 곳에서 몇 레벨 야생 포켓몬이 나오는지 알려 줘요. 사냥터 고를 때 써 봐요!", serverHas(SPAWN_LEVEL)) { command(SPAWN_LEVEL) },
            Entry("위키 열기", "서버에서 궁금한 건 거의 다 위키에 있어요. 내 진행도에 맞춘 정보도 같이 보여 줘요!", policy) { link(RequestLinkPayload.WIKI) },
            Entry("디스코드 열기", "디스코드에 오면 공지랑 전설, 이로치 소식을 받을 수 있어요. 들어와서 /디코인증도 꼭 해 줘요!", policy) { link(RequestLinkPayload.DISCORD) },
        )
    }

    private fun serverHas(command: String): Boolean =
        minecraft?.connection?.commands?.root?.getChild(command) != null

    private fun command(text: String) {
        val client = minecraft!!
        client.setScreen(null)
        client.player?.connection?.sendCommand(text)
    }

    private fun link(kind: String) {
        ClientPlayNetworking.send(RequestLinkPayload(kind))
    }

    /** `/mcc` opens the hub on its first tab; [MccHubTabRequest] moves it to [tab] once it shows. */
    private fun hub(tab: String) {
        MccHubTabRequest.request(tab)
        command("mcc")
    }

    private fun leftWidth() = (width * 0.3f).toInt()

    private fun boxRect(): UiRect {
        val left = leftWidth() + MARGIN
        val boxWidth = (width - left - MARGIN).coerceAtLeast(120)
        return UiRect(left, height - BOX_HEIGHT - MARGIN, boxWidth, BOX_HEIGHT)
    }

    private fun drawBox(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val theme = UiDialogueStyle.theme()
        val ink = UiDialogueStyle.ink(theme)
        val shadow = UiDialogueStyle.inkShadow(theme)
        val box = boxRect()
        // The same message box the NPC and battle lines speak in.
        UiDialogueStyle.drawBox(graphics, theme, box)
        val name = Component.literal("피츄")
        UiDialogueStyle.drawPlate(graphics, theme, font, UiRect(box.x + 8, box.y - 14, font.width(name) + 16, 16), name, box.x + 16)

        val textLeft = box.x + PADDING
        val wrapped = font.splitter.splitLines(shown, box.right - PADDING - 12 - textLeft, Style.EMPTY).map { it.string }
        var budget = ((System.nanoTime() - revealStart) / 1e9 * CHARACTERS_PER_SECOND).toInt()
        wrapped.take(MAX_LINES).forEachIndexed { index, line ->
            if (budget <= 0) return@forEachIndexed
            val part = if (budget >= line.length) line else line.substring(0, budget)
            budget -= line.length
            UiTextRenderer.draw(graphics, font, Component.literal(part), textLeft, box.y + 12 + index * LINE_HEIGHT, ink, shadow)
        }
        if (budget <= 0) return
        // The whole line is out: Pichu's little bobbing arrow, as in a talk.
        val arrowX = box.right - 20
        val baseY = box.bottom - 14 + (sin(System.nanoTime() / 180_000_000.0) * 1.5).toInt()
        UiDialogueStyle.drawArrow(graphics, theme, arrowX, baseY)
    }

    companion object {
        const val SPAWN_LEVEL = "구역스폰레벨"
        const val MYROOM_DIMENSION = "myroom:rooms"
        const val SHOP_TAB = "more_cobblemon_contents:shop"
        const val PVP_TAB = "more_cobblemon_contents:pvp"
        private const val UNAVAILABLE = "음… 이건 지금 이 서버에서는 쓸 수 없어요. 미안해요!"
        private const val MARGIN = 12
        private const val COLUMN_GAP = 8
        private const val ROW_GAP = 6
        private const val BUTTON_HEIGHT = 20
        private const val BUTTON_MAX_WIDTH = 150
        private const val BOX_HEIGHT = 66
        private const val PADDING = 14
        private const val LINE_HEIGHT = 12
        private const val MAX_LINES = 4
        private const val CHARACTERS_PER_SECOND = 45.0
        private const val IDLE_MILLIS = 7_000L
    }
}
