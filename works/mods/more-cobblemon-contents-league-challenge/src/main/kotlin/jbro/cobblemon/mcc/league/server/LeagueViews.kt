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
        /** What becoming this route's Champion opens. */
        fun championUnlocks(hard: Boolean): List<String> = if (hard) emptyList() else
            listOf(LIVE + "unlock.hard") + if (catalog.id == primary.id) {
                // The next region is named only once it is released.
                val next = LeagueCatalogResources.catalogs.values.any { it.active && it.unlockLeague == catalog.id }
                listOfNotNull((LIVE + "unlock.galar").takeIf { next }, LIVE + "unlock.gimmicks")
            } else emptyList()
        fun entry(id: String, hard: Boolean, stage: Int, branch: Int, run: LeagueRun? = null): LeagueChallengeView {
            val challenge = run?.encounters?.firstOrNull() ?: catalog.challenges.getValue(id)
            val stages = catalog.gymStages(hard)
            val final = stage > stages.size
            val finals = catalog.finals(hard)
            val reward = if (final) run?.encounters?.lastOrNull() ?: catalog.challenges.getValue(finals.last()) else challenge
            val won = if (final) (if (hard) state.hardChampion else state.champion) else id in state.cleared
            val available = unlocked && engine.available(state, id)
            val facing = run?.let { it.encounters[if (it.awaitingNext) minOf(it.index + 1, it.encounters.lastIndex) else it.index] } ?: challenge
            val unlocks = if (won) emptyList() else when {
                !final && stage < stages.size -> stages[stage].map { catalog.challenges.getValue(it).nameKey }
                !final -> listOf(LIVE + if (catalog.unlockLeague == null) "unlock.league" else "unlock.cup")
                else -> championUnlocks(hard)
            }
            // A displayed reward must be awarded by this challenge; hard rematches do not grant another badge.
            val badge = challenge.badge
            return LeagueChallengeView(id, facing.nameKey, if (won) "CLEARED" else if (available) "AVAILABLE" else "LOCKED",
                reward.unlockCap, badge, facing.skin, stage, branch, run?.battleCap ?: engine.battleCap(id),
                flags(run?.mechanic ?: engine.mechanic(state)), facing.type, facing.format,
                if (won) reward.repeatBp else reward.firstBp, unlocks, final)
        }
        fun route(hard: Boolean): List<LeagueChallengeView> {
            val stages = catalog.gymStages(hard)
            val finals = catalog.finals(hard)
            if (stages.isEmpty()) return emptyList()
            return stages.flatMapIndexed { index, alternatives -> alternatives.mapIndexed { branch, id -> entry(id, hard, index + 1, branch) } } +
                entry(finals.first(), hard, stages.size + 1, 0, state.run?.takeIf { it.encounters.first().id == finals.first() })
        }
        /** The finals' trainers in order; a run on them marks who is beaten and who is faced now. */
        fun finalists(hard: Boolean): List<LeagueChallengeView> {
            val finals = catalog.finals(hard)
            if (finals.isEmpty()) return emptyList()
            val run = state.run?.takeIf { it.encounters.first().id == finals.first() }
            val encounters = run?.encounters ?: finals.map { catalog.challenges.getValue(it) }
            val current = run?.let { if (it.awaitingNext) it.index + 1 else it.index }
            val won = if (hard) state.hardChampion else state.champion
            val open = unlocked && engine.available(state, finals.first())
            val mechanic = flags(run?.mechanic ?: engine.mechanic(state))
            val cap = run?.battleCap ?: engine.battleCap(finals.first())
            return encounters.mapIndexed { index, challenge ->
                val status = when {
                    current != null -> if (index < current) "CLEARED" else if (index == current) "AVAILABLE" else "LOCKED"
                    won -> "CLEARED"
                    open && index == 0 -> "AVAILABLE"
                    else -> "LOCKED"
                }
                val beaten = challenge.id in state.cleared
                val last = index == encounters.lastIndex
                LeagueChallengeView(challenge.id, challenge.nameKey, status, challenge.unlockCap, null, challenge.skin,
                    index + 1, 0, cap, mechanic, challenge.type, challenge.format,
                    if (beaten) challenge.repeatBp else challenge.firstBp,
                    if (last && !won) championUnlocks(hard) else emptyList(), true)
            }
        }
        fun branches(hard: Boolean): List<LeagueFinalBranchView> =
            (if (hard) catalog.hardFinalChoices else catalog.finalChoices).values.flatten().distinct().map {
                LeagueFinalBranchView(it, catalog.challenges.getValue(it).nameKey)
            }
        val hard = state.run?.let { it.hard ?: catalog.isHard(it.encounters.first().id) } ?: false
        val runEntry = state.run?.let { run ->
            val first = run.encounters.first().id
            val stages = catalog.gymStages(hard)
            val index = stages.indexOfFirst { first in it }
            if (index >= 0) entry(first, hard, index + 1, stages[index].indexOf(first), run)
            else entry(first, hard, stages.size + 1, 0, run)
        }
        return LeagueView(nonce, state.revision, LeagueCatalogResources.revision, catalog.nameKey,
            engine.badgeCount(state), LeagueServer.rank(primary, states.getValue(primary.id)).name,
            LeagueProgressQueries.cap(player), state.champion, BattlePointRewards.balance(player.server, player.uuid),
            route(false), state.run?.challengeId, state.run?.awaitingNext ?: false,
            states.values.any { p -> p.rewards.any { !it.badgeDone || !it.bpDone } }, error, open,
            state.run?.let { it.encounters[it.index].nameKey }, engine.hardUnlocked(state), state.hardChampion,
            route(true), hard, catalog.id,
            LeagueCatalogResources.catalogs.values.filter { it.active }.map { LeagueRegionView(it.id, it.nameKey, LeagueWorldRules.unlocked(it, states)) },
            unlocked, branches(false), branches(true), runEntry, finalists(false), finalists(true))
    }
}
