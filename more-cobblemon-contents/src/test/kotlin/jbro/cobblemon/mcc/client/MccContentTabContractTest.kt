package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.hub.BattleHubContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MccContentTabContractTest {
    @Test
    fun `tabs are ordered shop PvP tower factory boss and shop is the default`() {
        assertEquals(
            listOf(
                BattleHubContent.SHOP,
                BattleHubContent.PVP,
                BattleHubContent.BATTLE_TOWER,
                BattleHubContent.BATTLE_FACTORY,
                BattleHubContent.BOSS_RAID,
            ),
            MccContentTabContract.DISPLAY_ORDER,
        )
        assertEquals(BattleHubContent.SHOP, MccContentTabContract.DEFAULT_CONTENT)
        assertEquals(
            listOf(
                BattleHubContent.BATTLE_TOWER,
                BattleHubContent.BATTLE_FACTORY,
                BattleHubContent.PVP,
                BattleHubContent.BOSS_RAID,
            ),
            BattleHubContent.entries.take(4),
            "the legacy content ordinals must remain stable for partial deployment",
        )
    }
}
