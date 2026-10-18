package jbro.cobblemon.mcc.internal.ai

/**
 * The board's reading of what an attack's own stat changes (Scale Shot's Speed and Defense, Close Combat's defences,
 * Draco Meteor's Special Attack) are worth to the Pokemon that uses it, for [BattleOpponentMoveExpectationScorer]:
 * the mean change in its one-on-one worth, `(score + 1) / 2`, against every living Pokemon of the other side (the
 * Pokemon it will face), from using that attack before and after the change. A set-level flat value (50 a raised stage,
 * 5% a dropped defence) read Scale Shot's Speed the same whether or not it turned any exchange (user decision
 * 2026-10-18: stat changes are valued on the board and the opposing team).
 */
fun interface BattleOwnStageValue {
    /** The worth change, or null when the board cannot read it (the scorer then keeps its set-level value). */
    fun worthChange(pokemon: BattlePokemonStateView, moveId: String, move: BattleMoveCandidateView): Double?
}
