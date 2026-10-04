package kr.parkjh.pokefusion

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PendingOutputLedgerTest {
    @Test
    fun `failed outputs survive a new menu view and successful outputs are removed`() {
        val playerId = UUID.randomUUID()
        val ledger = PendingOutputLedger<String>()
        ledger.enqueue(playerId, listOf("fusion-result", "held-item"))

        ledger.deliver(playerId) { it == "fusion-result" }
        assertEquals(listOf("held-item"), ledger.snapshot(playerId))

        val reopenedView = ledger.snapshot(playerId)
        assertEquals(listOf("held-item"), reopenedView)
        ledger.deliver(playerId) { true }
        assertEquals(emptyList<String>(), ledger.snapshot(playerId))
    }

    @Test
    fun `players never see each others pending outputs`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val ledger = PendingOutputLedger<String>()

        ledger.enqueue(first, listOf("first-result"))
        ledger.enqueue(second, listOf("second-result"))

        assertEquals(listOf("first-result"), ledger.snapshot(first))
        assertEquals(listOf("second-result"), ledger.snapshot(second))
    }
}
