package jbro.cobblemon.morebattlecontent.betterai.calculation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

internal data class LocalForcedReplacementResolution(
    val states: List<BattleStateView>,
    val publiclyKnownFraction: Double,
)

/** Builds only publicly known forced replacements and reports how much of the reserve is known. */
internal object LocalForcedReplacementResolver {
    fun resolve(
        state: BattleStateView,
        side: BattleSide,
        source: BattleDecisionContext,
    ): LocalForcedReplacementResolution {
        val active = state.pokemon.filter {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val remaining = state.remainingPokemonBySide.getValue(side)
        if (remaining <= 0) return LocalForcedReplacementResolution(emptyList(), 1.0)
        val slotCapacity = if (state.format == BattleFormat.DOUBLE) 2 else 1
        val desiredActiveCount = minOf(slotCapacity, remaining)
        val missingCount = (desiredActiveCount - active.size).coerceAtLeast(0)
        if (missingCount == 0) return LocalForcedReplacementResolution(emptyList(), 1.0)
        val occupiedSlots = active.mapNotNullTo(linkedSetOf()) { it.activeSlot }
        val missingSlots = (0 until slotCapacity).filterNot(occupiedSlots::contains).take(missingCount)
        val knownBench = state.pokemon.filter {
            it.side == side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
        }
        val states = orderedSelections(knownBench, missingSlots.size).map { replacements ->
            missingSlots.zip(replacements).fold(state) { projected, (slot, bench) ->
                val raw = BattleActionCandidate(
                    actionId = "lookahead:${side.name.lowercase()}:forced:slot:$slot:${bench.battlePokemonId}",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = slot,
                    switchPokemonId = bench.battlePokemonId,
                    tags = setOf("public_lookahead", "forced_replacement"),
                )
                val calculated = PublicBattleTacticalCalculator.calculate(
                    BattleDecisionContext(
                        requestId = source.requestId,
                        state = projected,
                        candidates = listOf(raw),
                        deadlineEpochMillis = source.deadlineEpochMillis,
                        publicActionCatalog = source.publicActionCatalog,
                    ),
                    side,
                ).candidates.single()
                LocalSwitchStateProjector.project(projected, side, calculated)
            }
        }
        val reserveCount = (remaining - active.size).coerceAtLeast(missingSlots.size)
        return LocalForcedReplacementResolution(
            states = states,
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
