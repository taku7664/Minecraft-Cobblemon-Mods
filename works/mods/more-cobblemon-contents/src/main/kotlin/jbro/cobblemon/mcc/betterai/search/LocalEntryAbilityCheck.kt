package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import java.util.UUID

/**
 * The abilities an opposing Pokemon on the field cannot have because they announce themselves on entry and it entered
 * without a word: Pressure, Intimidate, a weather or terrain setter. Each candidate ability is lent to it in the engine
 * on this board and only its entry events run ([jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker.entryAnnouncingAbilities]),
 * so a condition on the announcement (Supreme Overlord only with fainted allies, Anticipation only against a
 * super-effective move) is the engine's own. No ability is named.
 *
 * The board has to be the one it entered on, so the caller asks once, at the first decision after the entry.
 * Measured 2026-10-12: 4-8 ms warm (one world planned 2.5-4.6, the battle built 0.7-2.9, two or three entries
 * replayed 0.1-0.5), about 110 ms the first time after start.
 */
internal object LocalEntryAbilityCheck {
    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> 1 })

    /**
     * Per Pokemon of [subjects] (opposing, on the field, ability unknown, two or more candidates), the candidate
     * abilities its entry would have announced. Null when the engine could not play this board.
     */
    fun ruledOut(context: BattleDecisionContext, tier: BattleTrainerTier, subjects: Set<UUID>): Map<UUID, Set<String>>? {
        val state = context.state
        val foes = state.pokemon.filter { it.battlePokemonId in subjects && it.side == BattleSide.OPPONENT && it.activeSlot != null &&
            !it.fainted && it.hpFraction > 0.0 && it.knownAbilityId == null }
            .associate { it.battlePokemonId to LocalEntryAbilityProjector.publicPossibleAbilities(state, it) }
            .filterValues { it.size >= 2 }
        if (foes.isEmpty()) return emptyMap()
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            state.turn in 0..1 && jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules.acceptsObservations(state) ->
                planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        val world = plan.worlds.firstOrNull() ?: return null
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        return LocalEngineStopProbe.lease.withWorker(deadline) { worker ->
            val root = live?.let { worker.createLiveBattle(it, world.definition) } ?: worker.createBattle(world.definition)
            foes.mapValues { (id, abilities) -> worker.entryAnnouncingAbilities(root.snapshotJson, id.toString(), abilities) }
                .filterValues { it.isNotEmpty() }
        }
    }
}
