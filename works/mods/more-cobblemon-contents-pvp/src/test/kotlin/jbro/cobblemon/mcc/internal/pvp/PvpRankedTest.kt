package jbro.cobblemon.mcc.internal.pvp

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedIntent
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedIntentPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedLeader
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedStatePayload
import kotlin.math.roundToInt
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRankedTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)

    @Test
    fun `elo follows the Showdown ladder`() {
        // Both at the floor: K is 90 for the win and 10 for the loss, which the floor then holds at 1000.
        assertEquals(1045, PvpElo.next(1000.0, 1000.0, 1.0).roundToInt())
        assertEquals(1000, PvpElo.next(1000.0, 1000.0, 0.0).roundToInt())
        // 1200..1350 and above 1600 use K 32; 1350..1600 uses K 40.
        assertEquals(1316, PvpElo.next(1300.0, 1300.0, 1.0).roundToInt())
        assertEquals(1420, PvpElo.next(1400.0, 1400.0, 1.0).roundToInt())
        assertEquals(1684, PvpElo.next(1700.0, 1700.0, 0.0).roundToInt())
    }

    @Test
    fun `a battle moves the ladder once and ranks share equal ratings`() {
        val ladder = PvpRankedLadder()
        val battle = UUID(1, 1)
        val result = ladder.settle(battle, PvpBattleFormat.SINGLE, a, "A", b, "B")!!
        assertEquals(1045, result.winnerAfter.rating.roundToInt())
        assertEquals(1, result.winnerAfter.wins)
        assertEquals(1, result.loserAfter.losses)
        assertNull(ladder.settle(battle, PvpBattleFormat.SINGLE, a, "A", b, "B"))

        val top = ladder.standing(a, PvpBattleFormat.SINGLE)
        assertEquals(1, top.rank)
        assertEquals(2, top.ladderSize)
        assertEquals(2, ladder.standing(b, PvpBattleFormat.SINGLE).rank)
        // Doubles is a ladder of its own.
        assertEquals(0, ladder.standing(a, PvpBattleFormat.DOUBLE).rank)
        assertEquals(1000, ladder.standing(c, PvpBattleFormat.SINGLE).rating)
        assertEquals(listOf("A", "B"), ladder.top(PvpBattleFormat.SINGLE, 10).map { it.name })
    }

    @Test
    fun `close ratings meet at once and far ones once the window opens`() {
        val queue = PvpRankedQueue()
        queue.join(PvpRankedEntry(a, PvpBattleFormat.SINGLE, 1000.0, 0))
        queue.join(PvpRankedEntry(b, PvpBattleFormat.SINGLE, 1400.0, 0))
        queue.join(PvpRankedEntry(c, PvpBattleFormat.DOUBLE, 1000.0, 0))
        assertTrue(queue.pair(1_000).isEmpty())
        // 400 apart needs the window at 400: six steps of 50 over 100, which is 60 seconds, when it opens anyway.
        assertTrue(queue.pair(59_000).isEmpty())
        val pairs = queue.pair(60_000)
        assertEquals(1, pairs.size)
        assertEquals(setOf(a, b), setOf(pairs[0].first.playerId, pairs[0].second.playerId))
        // The doubles player still waits alone.
        assertEquals(listOf(c), queue.entries().map { it.playerId })
    }

    @Test
    fun `the closest rating is picked and players in a battle are passed over`() {
        val queue = PvpRankedQueue()
        queue.join(PvpRankedEntry(a, PvpBattleFormat.SINGLE, 1000.0, 0))
        queue.join(PvpRankedEntry(b, PvpBattleFormat.SINGLE, 1090.0, 1))
        queue.join(PvpRankedEntry(c, PvpBattleFormat.SINGLE, 1010.0, 2))
        assertTrue(queue.pair(3) { it != c }.single().let { it.first.playerId == a && it.second.playerId == b })
        queue.clear()
        queue.join(PvpRankedEntry(a, PvpBattleFormat.SINGLE, 1000.0, 0))
        queue.join(PvpRankedEntry(b, PvpBattleFormat.SINGLE, 1090.0, 1))
        queue.join(PvpRankedEntry(c, PvpBattleFormat.SINGLE, 1010.0, 2))
        val pair = queue.pair(3).single()
        assertEquals(a, pair.first.playerId)
        assertEquals(c, pair.second.playerId)
    }

    @Test
    fun `the last opponent waits a while before a rematch`() {
        val queue = PvpRankedQueue()
        queue.join(PvpRankedEntry(a, PvpBattleFormat.SINGLE, 1000.0, 0))
        queue.join(PvpRankedEntry(b, PvpBattleFormat.SINGLE, 1000.0, 0))
        assertEquals(1, queue.pair(0).size)
        queue.join(PvpRankedEntry(a, PvpBattleFormat.SINGLE, 1000.0, 100_000))
        queue.join(PvpRankedEntry(b, PvpBattleFormat.SINGLE, 1000.0, 100_000))
        assertTrue(queue.pair(110_000).isEmpty())
        assertEquals(1, queue.pair(130_000).size)
    }

    @Test
    fun `ranked payloads round trip`() {
        val state = PvpRankedStatePayload(
            standings = listOf(
                PvpRankedStanding(PvpBattleFormat.SINGLE, 1045, 1045, 1, 0, 1, 2),
                PvpRankedStanding(PvpBattleFormat.DOUBLE, 1000, 1000, 0, 0, 0, 0),
            ),
            queuedFormat = PvpBattleFormat.DOUBLE,
            queuedForMillis = 12_345,
            queueSizes = mapOf(PvpBattleFormat.SINGLE to 0, PvpBattleFormat.DOUBLE to 1),
            leaders = mapOf(PvpBattleFormat.SINGLE to listOf(PvpRankedLeader(1, "A", 1045, 1, 0))),
            messageKey = "key",
            messageArgs = listOf("x"),
            reopen = true,
        )
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        PvpRankedStatePayload.CODEC.encode(buffer, state)
        assertEquals(state, PvpRankedStatePayload.CODEC.decode(buffer))

        listOf(PvpRankedIntent.Refresh, PvpRankedIntent.Join(PvpBattleFormat.SINGLE), PvpRankedIntent.Leave).forEach { intent ->
            val intentBuffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
            PvpRankedIntentPayload.CODEC.encode(intentBuffer, PvpRankedIntentPayload(intent))
            assertEquals(intent, PvpRankedIntentPayload.CODEC.decode(intentBuffer).intent)
        }
    }

    @Test
    fun `an over-long message or name is cut instead of failing the state`() {
        val long = "x".repeat(1_000)
        val state = PvpRankedStatePayload(
            standings = emptyList(),
            queuedFormat = null,
            queuedForMillis = 0,
            queueSizes = emptyMap(),
            leaders = mapOf(PvpBattleFormat.SINGLE to List(20) { PvpRankedLeader(it + 1, long, 1000, 0, 0) }),
            messageKey = long,
            messageArgs = List(8) { long },
        )
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        PvpRankedStatePayload.CODEC.encode(buffer, state)
        val decoded = PvpRankedStatePayload.CODEC.decode(buffer)
        assertEquals(10, decoded.leaders.getValue(PvpBattleFormat.SINGLE).size)
        assertEquals(256, decoded.messageKey!!.length)
        assertEquals(4, decoded.messageArgs.size)
    }
}
