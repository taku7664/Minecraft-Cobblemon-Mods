package jbro.cobblemon.policy.legend

import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/** League ranks for the capture gate. Without League Challenge installed nothing is gated. */
internal object LegendRanks {
    sealed interface Verdict {
        data object Allowed : Verdict
        data class TooLow(val needed: LegendRank) : Verdict
        data object Unknown : Verdict
    }

    private val leagueLoaded by lazy { FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge") }

    fun check(player: ServerPlayer, needed: LegendRank): Verdict {
        if (!leagueLoaded) return Verdict.Allowed
        // Fail closed: a League storage error must not open Champion-only Legends to everyone.
        val rank = try { LeagueRanks.of(player.server, player.uuid) } catch (failure: RuntimeException) {
            JbroPolicy.LOGGER.warn("Could not read the League rank of {}", player.uuid, failure)
            null
        } ?: return Verdict.Unknown
        return if (rank.ordinal >= LeagueRank.valueOf(needed.name).ordinal) Verdict.Allowed else Verdict.TooLow(needed)
    }

    /** League Challenge's own translation of the rank name. */
    fun name(rank: LegendRank): Component =
        Component.translatable("screen.more_cobblemon_contents_league_challenge.home.rank." + rank.name.lowercase())
}
