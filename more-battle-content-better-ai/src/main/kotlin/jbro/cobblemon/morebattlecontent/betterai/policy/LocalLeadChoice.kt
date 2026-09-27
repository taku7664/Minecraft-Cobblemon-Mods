package jbro.cobblemon.morebattlecontent.betterai.policy

import java.util.Locale
import java.util.SplittableRandom
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleLeadChoiceContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness
import kotlin.math.exp
import kotlin.math.ln

/**
 * Picks the lead from the opponent's team preview, the way a player looks at six Pokemon and sends
 * out the one that plays best against them.
 *
 * Each own Pokemon is scored against every previewed opponent, since the AI does not know which of
 * them the player brings or leads with:
 * - Standard: type matchup only, the best attacking type against the opponent's types versus the
 *   opponent's types against its own.
 * - Advanced and Boss: also move power, the attacking and defending stats, and a sure speed edge.
 *
 * The lead is drawn from a softmax over those scores, sharper at higher tiers, so the same preview
 * does not always get the same lead. An Introductory trainer keeps the team order.
 */
internal object LocalLeadChoice {
    data class Choice(val leads: List<UUID>, val scores: Map<UUID, Double>)

    fun choose(context: BattleLeadChoiceContext): Choice? {
        val tier = context.trainerProfile.difficulty.tier
        if (tier == BattleTrainerTier.INTRODUCTORY) return null
        val foes = context.opponentTeamPreview.pokemon.filter { it.knownTypeIds.isNotEmpty() }
        val candidates = context.ownTeam.filter { !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty() || candidates.size < context.leadCount) return null
        val detailed = tier != BattleTrainerTier.STANDARD
        val scores = candidates.associate { own ->
            own.battlePokemonId to foes.map { foe -> matchup(own, foe, context, detailed) }.average()
        }
        val random = SplittableRandom(context.seed)
        val remaining = scores.toMutableMap()
        val leads = mutableListOf<UUID>()
        repeat(context.leadCount) {
            val pick = draw(remaining, temperature(tier), random)
            leads += pick
            remaining.remove(pick)
        }
        return Choice(leads, scores)
    }

    /** Log-scale edge of [own] over [foe]: 1.0 is one doubling of damage in its favour. */
    internal fun matchup(
        own: BattlePokemonStateView,
        foe: BattleOpponentTeamPreviewPokemonView,
        context: BattleLeadChoiceContext,
        detailed: Boolean,
    ): Double {
        val offence = context.ownMoves.forPokemon(own.battlePokemonId)
            .filter { it.details.damageCategory != BattleMoveDamageCategory.STATUS && it.details.power > 0.0 }
            .maxOfOrNull { move ->
                val stab = if (own.knownTypeIds.any { canonical(it) == canonical(move.details.typeId) }) STAB else 1.0
                var value = StandardTypeEffectiveness.multiplier(move.details.typeId, foe.knownTypeIds) * stab
                if (detailed) {
                    val physical = move.details.damageCategory == BattleMoveDamageCategory.PHYSICAL
                    value *= move.details.power / REFERENCE_POWER * statRatio(
                        own.combatStats?.let { if (physical) it.attack else it.specialAttack },
                        foe.combatStats?.let { if (physical) it.defence else it.specialDefence },
                    )
                }
                value
            } ?: MINIMUM_EDGE
        val defence = foe.knownTypeIds.maxOf { type ->
            StandardTypeEffectiveness.multiplier(type, own.knownTypeIds) * STAB
        }.let { value ->
            if (!detailed) value else value * statRatio(foe.combatStats?.let(::strongerAttack), own.combatStats?.let(::weakerDefence))
        }
        var edge = log2(offence.coerceAtLeast(MINIMUM_EDGE)) - log2(defence.coerceAtLeast(MINIMUM_EDGE))
        if (detailed) edge += speedEdge(own.combatStats?.speed, foe.combatStats?.speed)
        return edge
    }

    private fun draw(scores: Map<UUID, Double>, temperature: Double, random: SplittableRandom): UUID {
        val best = scores.values.max()
        val weights = scores.mapValues { (_, score) -> exp((score - best) / temperature) }
        var target = random.nextDouble(weights.values.sum())
        for ((id, weight) in weights) {
            target -= weight
            if (target < 0.0) return id
        }
        return weights.keys.last()
    }

    private fun temperature(tier: BattleTrainerTier): Double = when (tier) {
        BattleTrainerTier.INTRODUCTORY, BattleTrainerTier.STANDARD -> 0.6
        BattleTrainerTier.ADVANCED -> 0.4
        BattleTrainerTier.BOSS -> 0.3
    }

    private fun speedEdge(own: BattleIntegerRange?, foe: BattleIntegerRange?): Double = when {
        own == null || foe == null -> 0.0
        own.minimum > foe.maximum -> SPEED_EDGE
        own.maximum < foe.minimum -> -SPEED_EDGE
        else -> 0.0
    }

    private fun statRatio(attack: BattleIntegerRange?, defence: BattleIntegerRange?): Double {
        if (attack == null || defence == null) return 1.0
        val defending = midpoint(defence)
        return if (defending <= 0.0) 1.0 else (midpoint(attack) / defending).coerceIn(0.25, 4.0)
    }

    private fun strongerAttack(stats: BattleCombatStatRangesView): BattleIntegerRange =
        if (midpoint(stats.attack) >= midpoint(stats.specialAttack)) stats.attack else stats.specialAttack

    private fun weakerDefence(stats: BattleCombatStatRangesView): BattleIntegerRange =
        if (midpoint(stats.defence) <= midpoint(stats.specialDefence)) stats.defence else stats.specialDefence

    private fun midpoint(range: BattleIntegerRange): Double = (range.minimum + range.maximum) / 2.0

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private const val STAB = 1.5
    private const val REFERENCE_POWER = 80.0
    private const val SPEED_EDGE = 0.3
    /** Floor so an immunity or a Pokemon without attacks reads as a large but finite deficit. */
    private const val MINIMUM_EDGE = 0.125
}
