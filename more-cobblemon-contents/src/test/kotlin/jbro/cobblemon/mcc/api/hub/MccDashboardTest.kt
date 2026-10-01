package jbro.cobblemon.mcc.api.hub

import io.netty.buffer.Unpooled
import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.internal.hub.BattleHubDashboardPayload
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class MccDashboardTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun context(records: List<BattleHubRecordView>) = object : MccDashboardContext {
        override val server: MinecraftServer get() = error("no server in tests")
        override val playerId: java.util.UUID = java.util.UUID(0, 1)
        override val player: ServerPlayer? = null
        override fun records(contentId: String) = records.filter { it.contentId == contentId }
    }

    private val records = listOf(
        BattleHubRecordView("test:alpha", "single", 5, 2, 1, 3),
        BattleHubRecordView("test:beta", "single", 1, 1, 0, 1),
        BattleHubRecordView("test:beta", "double", 2, 0, 2, 2, mapOf("highest_floor" to 9)),
        BattleHubRecordView("test:gamma", "single", 0, 4, 0, 0),
    )

    @Test
    fun `each content gets its own card, a section where registered and its records otherwise`() {
        val own = MccDashboardCard("test:alpha", Component.literal("Alpha"), listOf(MccDashboardStat(Component.literal("Best"), Component.literal("3"))))
        val handles = listOf(
            MccDashboardSections.register("test:alpha") { own },
            // A section that shows nothing hides the content's card, records or not.
            MccDashboardSections.register("test:gamma") { null },
        )
        try {
            val cards = MccDashboardSections.build(context(records), records).filter { it.contentId.startsWith("test:") }
            assertEquals(listOf("test:alpha", "test:beta"), cards.map { it.contentId })
            assertEquals(own, cards[0])
            val beta = cards[1]
            assertEquals(3, beta.stats.size)
            assertEquals(listOf("double", "single"), beta.rows.map { (it.title.contents as net.minecraft.network.chat.contents.TranslatableContents).key.substringAfterLast('.') })
        } finally {
            handles.forEach(AutoCloseable::close)
        }
    }

    @Test
    fun `a failing section falls back to the content's records`() {
        val handle = MccDashboardSections.register("test:beta") { error("broken section") }
        try {
            val beta = MccDashboardSections.build(context(records), records).single { it.contentId == "test:beta" }
            assertEquals(2, beta.rows.size)
        } finally {
            handle.close()
        }
    }

    @Test
    fun `a content with no records has no plain card`() {
        assertNull(MccDashboardCards.records("test:none", emptyList()))
        assertEquals("71%", MccDashboardCards.winRate(5, 7).string)
        assertEquals("-", MccDashboardCards.winRate(0, 0).string)
    }

    @Test
    fun `cards stack in hub tab order with their own heights`() {
        val short = MccDashboardCard("test:b", Component.literal("B"))
        val tall = MccDashboardCard("test:a", Component.literal("A"),
            listOf(MccDashboardStat(Component.literal("x"), Component.literal("1"))),
            listOf(MccDashboardRow(Component.literal("r"), Component.literal("v"), Component.literal("d")), MccDashboardRow(Component.literal("r"), Component.literal("v"))),
            Component.literal("note"))
        val order = mapOf("test:a" to 2, "test:b" to 1)
        val presentation = MccDashboardPresentation.from(BattleHubDashboardPayload(10, 7, listOf(tall, short))) { order.getValue(it) }
        assertEquals(listOf("test:b", "test:a"), presentation.cards.map { it.card.contentId })
        assertEquals(0, presentation.cards[0].top)
        assertEquals(presentation.cards[0].height + MccDashboardPresentation.GAP, presentation.cards[1].top)
        val expected = MccDashboardPresentation.HEADER + MccDashboardPresentation.STATS + MccDashboardPresentation.ROW_DETAIL +
            MccDashboardPresentation.ROW + MccDashboardPresentation.NOTE + MccDashboardPresentation.PADDING
        assertEquals(expected, presentation.cards[1].height)
        assertEquals(70, presentation.winRatePercent)
        assertTrue(MccDashboardPresentation.from(null).cards.isEmpty())
    }

    @Test
    fun `the dashboard payload round trips every card`() {
        val payload = BattleHubDashboardPayload(10, 7, listOf(
            MccDashboardCard("test:a", Component.translatable("x.title"),
                listOf(MccDashboardStat(Component.literal("Badges"), Component.literal("5/8"))),
                listOf(MccDashboardRow(Component.literal("Next"), Component.translatable("x.gym"), null),
                    MccDashboardRow(Component.literal("Single"), Component.literal("3W 1L"), Component.literal("best 4"))),
                Component.literal("Locked")),
            MccDashboardCard("test:b", Component.literal("B")),
        ))
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        BattleHubDashboardPayload.CODEC.encode(buffer, payload)
        assertEquals(payload, BattleHubDashboardPayload.CODEC.decode(buffer))
    }
}
