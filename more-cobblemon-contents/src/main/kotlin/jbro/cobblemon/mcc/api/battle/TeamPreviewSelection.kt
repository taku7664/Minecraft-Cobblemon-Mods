package jbro.cobblemon.mcc.api.battle

import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ActionCandidateAdapter
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicSpeciesInferenceKnowledge
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicTeamPreviewKnowledge
import kotlin.math.ln

/**
 * How a trainer picks the Pokemon it brings after seeing the opponent's team preview, scored the way the Better AI's
 * lead choice (LocalLeadChoice) scores a lead: each candidate against every previewed Pokemon, and the tier decides
 * what it reads and how closely the draw follows the scores.
 * - Introductory: does not read the preview.
 * - Standard: type matchup only.
 * - Advanced and Boss: also move power, the attacking and defending stats, and a sure speed edge.
 */
object TeamPreviewSelection {
    /** One Pokemon a trainer may bring, by its public facts. */
    data class Candidate(val speciesId: String, val formId: String?, val level: Int, val moveIds: List<String>)

    /** A candidate's types and the stat ranges a Pokemon of its species and level may have. */
    internal data class Facts(val typeIds: Set<String>, val stats: BattleCombatStatRangesView?)

    class Scorer internal constructor(
        /** How sharply a draw over the scores follows them: lower is closer to always taking the best. */
        val temperature: Double,
        private val scoreOf: (Candidate) -> Double?,
    ) {
        private val cache = HashMap<Candidate, Double?>()

        /** The candidate's average edge over the previewed Pokemon (1.0 is a doubling of damage), or null if unknown. */
        @Synchronized
        fun score(candidate: Candidate): Double? = cache.getOrPut(candidate) { scoreOf(candidate) }
    }

    /** The [tier]'s scorer against [preview], or null when the tier does not read the preview or nothing in it is known. */
    fun scorer(tier: BattleTrainerTier, preview: BattleOpponentTeamPreviewView): Scorer? =
        scorer(
            tier,
            Cobblemon173PublicTeamPreviewKnowledge.enrich(preview),
            { speciesId, formId, level ->
                Cobblemon173PublicSpeciesInferenceKnowledge.publicPreviewFacts(speciesId, formId, level)
                    ?.let { Facts(it.knownTypeIds, it.combatStats) }
            },
            Cobblemon173ActionCandidateAdapter::publicMoveDetails,
        )

    internal fun scorer(
        tier: BattleTrainerTier,
        preview: BattleOpponentTeamPreviewView,
        facts: (speciesId: String, formId: String?, level: Int) -> Facts?,
        moveDetails: (String) -> BattleMoveCandidateView?,
    ): Scorer? {
        if (tier == BattleTrainerTier.INTRODUCTORY) return null
        val foes = preview.pokemon.filter { it.knownTypeIds.isNotEmpty() }
        if (foes.isEmpty()) return null
        val detailed = tier != BattleTrainerTier.STANDARD
        return Scorer(temperature(tier)) { candidate ->
            val own = facts(candidate.speciesId, candidate.formId, candidate.level)?.takeIf { it.typeIds.isNotEmpty() }
                ?: return@Scorer null
            val moves = candidate.moveIds.mapNotNull(moveDetails)
            foes.map { foe -> edge(own.typeIds, own.stats, moves, foe.knownTypeIds, foe.combatStats, detailed) }.average()
        }
    }

    /**
     * Log-scale edge of a Pokemon with [ownTypes], [ownStats] and [ownMoves] over one with [foeTypes] and [foeStats]
     * (1.0 is one doubling of damage in its favour), as LocalLeadChoice measures a lead's matchup.
     */
    private fun edge(
        ownTypes: Set<String>,
        ownStats: BattleCombatStatRangesView?,
        ownMoves: List<BattleMoveCandidateView>,
        foeTypes: Set<String>,
        foeStats: BattleCombatStatRangesView?,
        detailed: Boolean,
    ): Double {
        val offence = ownMoves
            .filter { it.damageCategory != BattleMoveDamageCategory.STATUS && it.power > 0.0 }
            .maxOfOrNull { move ->
                val stab = if (ownTypes.any { canonical(it) == canonical(move.typeId) }) STAB else 1.0
                var value = StandardTypeEffectiveness.multiplier(move.typeId, foeTypes) * stab
                if (detailed) {
                    val physical = move.damageCategory == BattleMoveDamageCategory.PHYSICAL
                    value *= move.power / REFERENCE_POWER * statRatio(
                        ownStats?.let { if (physical) it.attack else it.specialAttack },
                        foeStats?.let { if (physical) it.defence else it.specialDefence },
                    )
                }
                value
            } ?: MINIMUM_EDGE
        val defence = foeTypes.maxOf { type ->
            StandardTypeEffectiveness.multiplier(type, ownTypes) * STAB
        }.let { value ->
            if (!detailed) value else value * statRatio(foeStats?.let(::strongerAttack), ownStats?.let(::weakerDefence))
        }
        var edge = log2(offence.coerceAtLeast(MINIMUM_EDGE)) - log2(defence.coerceAtLeast(MINIMUM_EDGE))
        if (detailed) edge += speedEdge(ownStats?.speed, foeStats?.speed)
        return edge
    }

    /** LocalLeadChoice's draw temperatures. */
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

    private fun canonical(value: String): String = PublicIds.canonical(value)

    private const val STAB = 1.5
    private const val REFERENCE_POWER = 80.0
    private const val SPEED_EDGE = 0.3
    /** Floor so an immunity or a Pokemon without attacks reads as a large but finite deficit. */
    private const val MINIMUM_EDGE = 0.125
}
