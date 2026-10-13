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
     * When this side's Pokemon in front is offered no switch with others left (singles): the opposing Pokemon's
     * candidate abilities that would not hold it in, provided some would (Shadow Tag, Arena Trap, Magnet Pull, read
     * from the engine's TrapPokemon event on this board). A trap with a visible cause (Mean Look, a binding move) holds
     * it in every world and rules nothing out. Null when there is nothing to read or the engine could not play it.
     */
    fun trapRuledOut(context: BattleDecisionContext, tier: BattleTrainerTier): Pair<UUID, Set<String>>? {
        val state = context.state
        if (state.format != jbro.cobblemon.mcc.internal.ai.BattleFormat.SINGLE) return null
        val kinds = context.candidates.map { it.kind }
        if (jbro.cobblemon.mcc.internal.ai.BattleActionKind.USE_MOVE !in kinds || jbro.cobblemon.mcc.internal.ai.BattleActionKind.SWITCH in kinds) return null
        fun active(side: BattleSide) = state.pokemon.singleOrNull { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        val ally = active(BattleSide.ALLY) ?: return null
        val foe = active(BattleSide.OPPONENT)?.takeIf { it.knownAbilityId == null } ?: return null
        if (state.pokemon.none { it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }) return null
        val abilities = LocalEntryAbilityProjector.publicPossibleAbilities(state, foe).takeIf { it.size >= 2 } ?: return null
        val root = root(context, tier) ?: return null
        val trapping = LocalEngineStopProbe.lease.withWorker(deadline(context)) { worker ->
            root(worker, context, root).let { frame ->
                worker.trappingAbilities(frame.snapshotJson, foe.battlePokemonId.toString(), ally.battlePokemonId.toString(), abilities)
            }
        } ?: return null
        if (trapping.isEmpty() || trapping.size == abilities.size) return foe.battlePokemonId to emptySet()
        return foe.battlePokemonId to abilities.filterTo(linkedSetOf()) { it !in trapping }
    }

    internal fun root(context: BattleDecisionContext, tier: BattleTrainerTier): jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition? {
        val state = context.state
        val plan = when {
            context.showdownState != null -> planner.planLive(context, tier)
            state.turn in 0..1 && jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules.acceptsObservations(state) ->
                planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        return plan.worlds.firstOrNull()?.definition
    }

    internal fun root(
        worker: jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker,
        context: BattleDecisionContext,
        definition: jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition,
    ) = context.showdownState?.let { worker.createLiveBattle(it, definition) } ?: worker.createBattle(definition)

    internal fun deadline(context: BattleDecisionContext) = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
        (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))

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
