package jbro.cobblemon.mcc.league.server

import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.terminal.HoloTerminal
import jbro.cobblemon.mcc.api.terminal.HoloTerminalPalette
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.minecraft.resources.ResourceLocation

/** The League's hologram terminal: the hub on its League tab, with the dashboard and the shop beside it. */
object LeagueTerminal {
    val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "league_terminal")
    lateinit var terminal: HoloTerminal
        private set

    fun register() {
        terminal = HoloTerminals.register(id, ManagedBattleContentIds.LEAGUE_CHALLENGE,
            listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, ManagedBattleContentIds.LEAGUE_CHALLENGE),
            // Champion gold over a royal violet base.
            HoloTerminalPalette(0x3A2466, 0x6C3FB0, 0xFFC844, 0xFFE38A, 0xFFE9A6, 0xE0A93A, 0xFFD36A))
    }
}
