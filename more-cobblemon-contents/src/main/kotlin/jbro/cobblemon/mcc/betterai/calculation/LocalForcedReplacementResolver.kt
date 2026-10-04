package jbro.cobblemon.mcc.betterai.calculation

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector

internal data class LocalForcedReplacementResolution(
    val states: List<BattleStateView>,
    val publiclyKnownFraction: Double,
)

internal data class LocalForcedReplacementPlans(
    val choices: List<List<BattleActionCandidate>>,
    val publiclyKnownFraction: Double,
)

/** Builds only publicly known forced replacements and reports how much of the reserve is known. */
internal object LocalForcedReplacementResolver {
    fun resolve(
        state: BattleStateView,
        side: BattleSide,
        source: BattleDecisionContext,
    ): LocalForcedReplacementResolution {
        val plans = plans(state, side)
        return LocalForcedReplacementResolution(plans.choices.map { actions ->
            actions.fold(state) { projected, raw ->
                val calculated = PublicBattleTacticalCalculator.calculate(source.copy(state = projected,
                    candidates = listOf(raw)), side).candidates.single()
                LocalSwitchStateProjector.project(projected, side, calculated)
            }
        }, plans.publiclyKnownFraction)
    }

    /** Trainer choices before either side's entering ability is allowed to run. */
    fun plans(state: BattleStateView, side: BattleSide): LocalForcedReplacementPlans {
        val active = state.pokemon.filter {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val remaining = state.remainingPokemonBySide.getValue(side)
        if (remaining <= 0) return LocalForcedReplacementPlans(emptyList(), 1.0)
        val slotCapacity = if (state.format == BattleFormat.DOUBLE) 2 else 1
        val desiredActiveCount = minOf(slotCapacity, remaining)
        val missingCount = (desiredActiveCount - active.size).coerceAtLeast(0)
        if (missingCount == 0) return LocalForcedReplacementPlans(emptyList(), 1.0)
        val occupiedSlots = active.mapNotNullTo(linkedSetOf()) { it.activeSlot }
        val missingSlots = (0 until slotCapacity).filterNot(occupiedSlots::contains).take(missingCount)
        val knownBench = state.pokemon.filter {
            it.side == side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
        }
        // Two empty slots and one Pokemon seen on the bench still fill the slot it can; asking for both left no
        // replacement at all, and the branch was scored with both slots empty.
        val fillable = minOf(missingSlots.size, knownBench.size)
        val choices = if (fillable == 0) emptyList() else orderedSelections(knownBench, fillable).map { replacements ->
            missingSlots.zip(replacements).map { (slot, bench) ->
                BattleActionCandidate(
                    actionId = "lookahead:${side.name.lowercase()}:forced:slot:$slot:${bench.battlePokemonId}",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = slot,
                    switchPokemonId = bench.battlePokemonId,
                    tags = setOf("public_lookahead", "forced_replacement"),
                )
            }
        }
        val reserveCount = (remaining - active.size).coerceAtLeast(missingSlots.size)
        return LocalForcedReplacementPlans(
            choices = choices,
            publiclyKnownFraction = if (reserveCount == 0) 1.0 else {
                (knownBench.size.toDouble() / reserveCount).coerceIn(0.0, 1.0)
            },
        )
    }

    private fun <T> orderedSelections(values: List<T>, count: Int): List<List<T>> {
        if (count == 0) return listOf(emptyList())
        if (values.size < count) return emptyList()
        return values.flatMapIndexed { index, value ->
            val remaining = values.toMutableList().also { it.removeAt(index) }
            orderedSelections(remaining, count - 1).map { listOf(value) + it }
        }
    }
}
