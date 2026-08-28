package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/**
 * How likely one side is to act before the other, from public information alone.
 *
 * The turn projector has always worked this out for itself. The facts the root ranking is built from
 * carry a field for it - `actsFirstProbability` - that nothing ever filled, so the ranking had no
 * notion of turn order at all and the whole subject lived inside the search. At the lowest difficulty
 * the search is one ply and its voice is scaled down twice over, which means the trainers a player
 * meets first are the ones least able to reason about who moves when.
 *
 * Both Speeds are treated as uniform over their public range, which is the honest reading of a range
 * that refuses IVs, EVs and nature: no value inside it is claimed to be likelier than another. Ties
 * split evenly, matching the coin flip the engine performs.
 */
internal object LocalPublicTurnOrder {
    fun actsFirstProbability(
        state: BattleStateView,
        actorSide: BattleSide,
        actorSlot: Int?,
        actorPriority: Int,
        opponentPriority: Int,
    ): Double? {
        if (actorPriority != opponentPriority) return if (actorPriority > opponentPriority) 1.0 else 0.0
        val actor = active(state, actorSide, actorSlot) ?: return null
        val opponent = state.pokemon.firstOrNull {
            it.side != actorSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val actorSpeed = effectiveSpeed(state, actor) ?: return null
        val opponentSpeed = effectiveSpeed(state, opponent) ?: return null
        val faster = uniformGreaterProbability(actorSpeed, opponentSpeed)
        return if (trickRoomActive(state)) 1.0 - faster else faster
    }

    /** P(a > b) plus half of P(a == b), for two independent uniform integer ranges. */
    fun uniformGreaterProbability(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val aValues = (a.second - a.first + 1).toLong()
        val bValues = (b.second - b.first + 1).toLong()
        if (aValues <= 0L || bValues <= 0L) return 0.5
        var greater = 0L
        var equal = 0L
        // Counted over b, so the cost is the width of one range rather than the product of both.
        for (bValue in b.first..b.second) {
            greater += (a.second.toLong() - maxOf(a.first, bValue + 1) + 1).coerceAtLeast(0L)
            if (bValue in a.first..a.second) equal++
        }
        return (greater + equal / 2.0) / (aValues * bValues).toDouble()
    }

    fun effectiveSpeed(state: BattleStateView, pokemon: BattlePokemonStateView): Pair<Int, Int>? {
        val speed = pokemon.combatStats?.speed ?: return null
        val stage = pokemon.statStages.entries
            .firstOrNull { canonical(it.key) in SPEED_ALIASES }?.value?.coerceIn(-6, 6) ?: 0
        val paralysis = if (canonical(pokemon.statusId.orEmpty()) in PARALYSIS_IDS) 0.5 else 1.0
        val tailwind = if (
            state.field.sideConditions.getValue(pokemon.side).any {
                val remaining = it.remainingTurns
                canonical(it.effectId) == TAILWIND && (remaining == null || remaining > 0)
            }
        ) {
            2.0
        } else {
            1.0
        }
        val multiplier = paralysis * tailwind
        return (applyStage(speed.minimum, stage) * multiplier).toInt().coerceAtLeast(1) to
            (applyStage(speed.maximum, stage) * multiplier).toInt().coerceAtLeast(1)
    }

    private fun trickRoomActive(state: BattleStateView): Boolean = state.field.roomEffects.any {
        val remaining = it.remainingTurns
        canonical(it.effectId) == TRICK_ROOM && (remaining == null || remaining > 0)
    }

    private fun active(state: BattleStateView, side: BattleSide, slot: Int?): BattlePokemonStateView? =
        state.pokemon.firstOrNull {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                (slot == null || it.activeSlot == slot)
        }

    private fun applyStage(value: Int, stage: Int): Int = (
        if (stage >= 0) value * (2 + stage) / 2 else value * 2 / (2 - stage)
        ).coerceAtLeast(1)

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val TAILWIND = "tailwind"
    private const val TRICK_ROOM = "trickroom"
    private val SPEED_ALIASES = setOf("speed", "spe")
    private val PARALYSIS_IDS = setOf("par", "paralysis", "paralyzed", "paralysed")
}
