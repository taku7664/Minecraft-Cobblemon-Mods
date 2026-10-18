package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * A foe's Max Move as a status move's user should fear it: a foe that may still Dynamax this turn hits with its Max
 * Moves. A Garchomp's Swords Dance into a Mimikyu that Dynamaxed and Max Starfall knocked it out (league run,
 * 2026-10-19).
 *
 * Read for status moves only (user decision 2026-10-19): their worth is in being used and surviving to use the raise.
 * Fed to every threat reading, the Max Move once made every lead Dynamax on turn 1 (2026-10-16, [LocalDynamaxGuard]).
 *
 * The battle has Dynamax by its rules ([BattleDecisionContext.dynamaxInBattle], which the game passes); without them,
 * when this side may (one of its candidates Dynamaxes) or either side already did. The foe's side has not spent it; the
 * foe is not Dynamaxed already, not Terastallized, and can (not a Mega, not a species barred from it).
 */
internal object LocalFoeDynamaxThreat {
    fun possible(context: BattleDecisionContext, foe: BattlePokemonStateView): Boolean {
        val state = context.state
        // Showdown's getDynamaxRequest: a Terastallized Pokemon cannot Dynamax.
        if (LocalDynamax.used(state, foe.side) || LocalDynamax.active(foe) || !LocalDynamax.canDynamax(foe) ||
            foe.knownTeraTypeId != null) return false
        context.dynamaxInBattle?.let { return it }
        val own = if (foe.side == BattleSide.OPPONENT) BattleSide.ALLY else BattleSide.OPPONENT
        return LocalDynamax.used(state, own) || context.candidates.any { candidate ->
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            parts.any { LocalDynamax.isMechanic(it.mechanic?.mechanicId) }
        }
    }

    /**
     * [foe]'s likeliest knockout of [user] this turn with its Max Moves, on [board] (the user's side as it will stand), or
     * null when it may not Dynamax ([possible]) or has no Max Move to read.
     */
    fun bestHit(
        context: BattleDecisionContext,
        board: BattleDecisionContext,
        user: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): LocalMatchupScoreCalculator.ScoredMove? = hits(context, board, user, foe, cache).maxByOrNull { it.score.knockoutChanceWithin(1) }

    /** Every Max Move [foe] may hit [user] with this turn ([bestHit]'s reading); empty when it may not Dynamax. */
    fun hits(
        context: BattleDecisionContext,
        board: BattleDecisionContext,
        user: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): List<LocalMatchupScoreCalculator.ScoredMove> {
        if (!possible(context, foe)) return emptyList()
        val dynamaxed = LocalDynamax.start(foe)
        val state = board.state.copyState(pokemon = board.state.pokemon.map { if (it.battlePokemonId == foe.battlePokemonId) dynamaxed else it })
        val faced = LocalMatchupPosition.face(board.copy(state = state), user, dynamaxed, cache) ?: return emptyList()
        return LocalMatchupScoreCalculator.moveMatchups(faced, foe.battlePokemonId, user.battlePokemonId, cache)
    }
}
