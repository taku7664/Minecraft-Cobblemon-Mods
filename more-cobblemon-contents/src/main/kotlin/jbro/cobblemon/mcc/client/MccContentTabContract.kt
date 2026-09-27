package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.hub.BattleHubContent

internal object MccContentTabContract {
    val DISPLAY_ORDER = listOf(
        BattleHubContent.SHOP,
        BattleHubContent.PVP,
        BattleHubContent.BATTLE_TOWER,
        BattleHubContent.BATTLE_FACTORY,
        BattleHubContent.BOSS_RAID,
    )
    val DEFAULT_CONTENT = BattleHubContent.SHOP
}
