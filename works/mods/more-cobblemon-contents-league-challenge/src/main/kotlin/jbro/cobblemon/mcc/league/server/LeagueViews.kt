package jbro.cobblemon.mcc.league.server

import java.util.UUID
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.api.rules.BattleMechanicFlags
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.network.*
import jbro.cobblemon.mcc.league.system.*
import net.minecraft.server.level.ServerPlayer

/** One authoritative view of the selected league, plus the cross-league entitlements. */
internal object LeagueViews {
    private const val LIVE = "screen.${Mod.MOD_ID}.live."

    fun build(player: ServerPlayer, nonce: UUID, catalog: LeagueCatalog, error: String?, open: Boolean): LeagueView {
        val states = LeagueProgressQueries.states(player.server, player.uuid)
        val state = states.getValue(catalog.id)
        val primary = requireNotNull(LeagueCatalogResources.current)
        val engine = LeagueEngine(catalog)
        val unlocked = LeagueWorldRules.unlocked(catalog, states)
        fun flags(mechanic: String): Int = BattleMechanicFlags.fromMajor(mechanic.takeUnless { it == "NONE" }?.let(MajorBattleMechanic::valueOf))
        fun entry(id: String, hard: Boolean, stage: Int, branch: Int, run: LeagueRun? = null): LeagueChallengeView {
            val challenge = run?.encounters?.firstOrNull() ?: catalog.challenges.getValue(id)
            val final = stage == 9
            val finals = if (hard) catalog.hardFinals else catalog.finals
            val reward = if (final) run?.encounters?.lastOrNull() ?: catalog.challenges.getValue(finals.last()) else challenge
            val won = if (final) (if (hard) state.hardChampion else state.champion) else id in state.cleared
            val available = unlocked && engine.available(state, id)
            val facing = run?.let { it.encounters[if (it.awaitingNext) minOf(it.index + 1, it.encounters.lastIndex) else it.index] } ?: challenge
            val stages = if (hard) catalog.hardGymStages else catalog.gymStages
            val unlocks = if (won) emptyList() else when {
                !final && stage < 8 -> stages[stage].map { catalog.challenges.getValue(it).nameKey }
                !final -> listOf(LIVE + if (catalog.unlockLeague == null) "unlock.league" else "unlock.cup")
                !hard -> listOf(LIVE + "unlock.hard") + if (catalog.id == primary.id)
                    listOf(LIVE + "unlock.galar", LIVE + "unlock.gimmicks") else emptyList()
                else -> emptyList()
            }
            // A displayed reward must be awarded by this challenge; hard rematches do not grant another badge.
            val badge = challenge.badge
            return LeagueChallengeView(id, facing.nameKey, if (won) "CLEARED" else if (available) "AVAILABLE" else "LOCKED",
                reward.unlockCap, badge, facing.skin, stage, branch, run?.battleCap ?: engine.battleCap(id),
                flags(run?.mechanic ?: engine.mechanic(state)), facing.type, facing.format,
                if (won) reward.repeatBp else reward.firstBp, unlocks)
        }
        fun route(hard: Boolean): List<LeagueChallengeView> {
            val stages = if (hard) catalog.hardGymStages else catalog.gymStages
            val finals = if (hard) catalog.hardFinals else catalog.finals
            if (stages.isEmpty()) return emptyList()
            return stages.flatMapIndexed { index, alternatives -> alternatives.mapIndexed { branch, id -> entry(id, hard, index + 1, branch) } } +
                entry(finals.first(), hard, 9, 0, state.run?.takeIf { it.encounters.first().id == finals.first() })
        }
        fun branches(hard: Boolean): List<LeagueFinalBranchView> =
            (if (hard) catalog.hardFinalChoices else catalog.finalChoices).values.flatten().distinct().map {
                LeagueFinalBranchView(it, catalog.challenges.getValue(it).nameKey)
            }
        val hard = state.run?.let { it.hard ?: (it.encounters.first().id in catalog.hardGyms + catalog.hardFinals) } ?: false
        val runEntry = state.run?.let { run ->
            val first = run.encounters.first().id
            val stages = if (hard) catalog.hardGymStages else catalog.gymStages
            val stage = stages.indexOfFirst { first in it }.let { if (it < 0) 9 else it + 1 }
            entry(first, hard, stage, if (stage <= 8) stages[stage - 1].indexOf(first) else 0, run)
        }
        return LeagueView(nonce, state.revision, LeagueCatalogResources.revision, catalog.nameKey,
            engine.badgeCount(state), LeagueServer.rank(primary, states.getValue(primary.id)).name,
            LeagueProgressQueries.cap(player), state.champion, BattlePointRewards.balance(player.server, player.uuid),
            route(false), state.run?.challengeId, state.run?.awaitingNext ?: false,
            states.values.any { p -> p.rewards.any { !it.badgeDone || !it.bpDone } }, error, open,
            state.run?.let { it.encounters[it.index].nameKey }, engine.hardUnlocked(state), state.hardChampion,
            route(true), hard, catalog.id,
            LeagueCatalogResources.catalogs.values.map { LeagueRegionView(it.id, it.nameKey, LeagueWorldRules.unlocked(it, states)) },
            unlocked, branches(false), branches(true), runEntry)
    }
}
