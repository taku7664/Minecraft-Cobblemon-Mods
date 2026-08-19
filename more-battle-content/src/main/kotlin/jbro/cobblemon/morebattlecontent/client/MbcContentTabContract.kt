package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubContent

internal object MbcContentTabContract {
    val DISPLAY_ORDER = listOf(
        BattleHubContent.SHOP,
        BattleHubContent.PVP,
        BattleHubContent.BATTLE_TOWER,
        BattleHubContent.BATTLE_FACTORY,
        BattleHubContent.BOSS_RAID,
    )
    val DEFAULT_CONTENT = BattleHubContent.SHOP
}
