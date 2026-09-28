package jbro.cobblemon.mcc.betterai.search

/**
 * How a doubles second turn is searched. The full one never finishes: the first turn times several
 * thousand root pairs is far past any node limit.
 *
 * - Only the first-turn leaders go deeper ([ROOT_CANDIDATES]); the rest keep their first-turn values.
 * - Under each, only the most likely opponent responses go deeper ([DEEP_RESPONSES], by the intent
 *   prediction). The others keep their one-turn value moved by how much going deeper moved the deep ones,
 *   so the responses stay comparable when the worst of them is taken. The one-turn values are the
 *   ones the first turn already computed.
 * - The second turn gives each side [INNER_PER_SLOT] candidates per slot.
 * - Going deeper shifts every leader by its own foresight; the shift they share is taken back out, so the
 *   leaders are reordered among themselves and not pushed past the candidates that were not searched.
 */
internal object LocalNarrowSecondTurn {
    const val ROOT_CANDIDATES = 3
    const val DEEP_RESPONSES = 3
    const val INNER_PER_SLOT = 3
}
