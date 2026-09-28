package jbro.cobblemon.mcc.betterai.search

/**
 * How a doubles second turn is searched. The full one never finishes: the first turn times several
 * thousand root pairs is far past any node limit.
 *
 * - Only the first-turn leaders go deeper ([ROOT_CANDIDATES]); the rest keep their first-turn values. Values
 *   are per turn, so a two-turn value and a one-turn value compare directly.
 * - Under each, only the most likely opponent responses go deeper ([DEEP_RESPONSES], by the intent
 *   prediction). The others keep their one-turn value, moved down (never up) by how much going deeper moved
 *   the deep ones: the reply that punishes the line may be one that was not searched. The one-turn values are
 *   the ones the first turn already computed.
 * - The second turn gives the AI [INNER_PER_SLOT] candidates per slot and the opponent
 *   [INNER_OPPONENT_PER_SLOT]: fewer own options only misses lines, fewer opponent options hides punishments.
 */
internal object LocalNarrowSecondTurn {
    const val ROOT_CANDIDATES = 3
    const val DEEP_RESPONSES = 3
    const val INNER_PER_SLOT = 3
    const val INNER_OPPONENT_PER_SLOT = 6
}
