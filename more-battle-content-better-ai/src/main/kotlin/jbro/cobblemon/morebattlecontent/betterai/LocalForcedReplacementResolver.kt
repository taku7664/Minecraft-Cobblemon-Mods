package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

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
        val activeExists = state.pokemon.any {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val remaining = state.remainingPokemonBySide.getValue(side)
        if (activeExists || remaining <= 0) return LocalForcedReplacementResolution(emptyList(), 1.0)
        val knownBench = state.pokemon.filter {
            it.side == side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
        }
        val states = knownBench.map { bench ->
            val raw = BattleActionCandidate(
                actionId = "lookahead:${side.name.lowercase()}:forced:${bench.battlePokemonId}",
                kind = BattleActionKind.SWITCH,
                actorSlot = 0,
                switchPokemonId = bench.battlePokemonId,
                tags = setOf("public_lookahead", "forced_replacement"),
            )
            val calculated = PublicBattleTacticalCalculator.calculate(
                BattleDecisionContext(
                    requestId = source.requestId,
                    state = state,
                    candidates = listOf(raw),
                    deadlineEpochMillis = source.deadlineEpochMillis,
                    publicActionCatalog = source.publicActionCatalog,
                ),
                side,
            ).candidates.single()
            LocalSwitchStateProjector.project(state, side, calculated)
        }
        return LocalForcedReplacementResolution(
            states = states,
            publiclyKnownFraction = (knownBench.size.toDouble() / remaining).coerceIn(0.0, 1.0),
        )
    }
}
