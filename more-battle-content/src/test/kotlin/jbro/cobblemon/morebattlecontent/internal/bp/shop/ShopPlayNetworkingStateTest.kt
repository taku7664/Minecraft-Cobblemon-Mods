package jbro.cobblemon.morebattlecontent.internal.bp.shop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopPlayNetworkingStateTest {
    @Test
    fun `missing catalog produces a visible closed failure state`() {
        val state = shopStatePayload(null, 37L, null)

        assertEquals(37L, state.balanceBp)
        assertTrue(state.entries.isEmpty())
        assertEquals(BattlePointShopPurchaseStatus.CATALOG_UNAVAILABLE, state.result)
        assertEquals(BattlePointShopLimits(1, 1, 1), state.limits)
    }
}
