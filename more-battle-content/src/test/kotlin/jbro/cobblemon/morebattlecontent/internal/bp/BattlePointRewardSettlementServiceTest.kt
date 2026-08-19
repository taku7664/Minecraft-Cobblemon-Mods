package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattlePointRewardSettlementServiceTest {
    @Test
    fun `content reward uses the settlement id as its persistent idempotency key`() {
        val store = BattlePointStore { 10L }
        val service = BattlePointRewardSettlementService(store::apply)
        val settlement = BattlePointRewardSettlement(
            settlementId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            contentId = BattleContentId("battle_tower"),
            amount = 12,
            reason = "tower_win",
        )

        val applied = service.settle(settlement)
        val retry = service.settle(settlement)

        assertEquals(BattlePointApplyStatus.APPLIED, applied.status)
        assertEquals(BattlePointApplyStatus.ALREADY_APPLIED, retry.status)
        assertEquals(BattlePointTransactionKind.CONTENT_REWARD, applied.transaction?.kind)
        assertEquals(
            BattlePointSourceId("cobblemon_more_battle_content:battle_tower"),
            applied.transaction?.sourceId,
        )
        assertEquals(12, store.balance(settlement.playerId))
    }

    @Test
    fun `standard content victory awards two BP once`() {
        val store = BattlePointStore { 10L }
        val service = BattlePointRewardSettlementService(store::apply)
        val battleId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        val applied = service.settleVictory(battleId, playerId, BattleContentId("battle_factory"))
        val retry = service.settleVictory(battleId, playerId, BattleContentId("battle_factory"))

        assertEquals(BattlePointApplyStatus.APPLIED, applied.status)
        assertEquals(BattlePointApplyStatus.ALREADY_APPLIED, retry.status)
        assertEquals(2L, applied.transaction?.requestedValue)
        assertEquals("battle_factory_win", applied.transaction?.reason)
        assertEquals(2L, store.balance(playerId))
    }
}
