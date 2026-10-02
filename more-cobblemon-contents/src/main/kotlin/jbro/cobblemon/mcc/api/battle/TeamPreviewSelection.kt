package jbro.cobblemon.mcc.api.battle

import jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ActionCandidateAdapter
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicSpeciesInferenceKnowledge
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicTeamPreviewKnowledge

/**
 * How a trainer picks the Pokemon it brings after seeing the opponent's team preview, the way the Better AI picks
 * its lead: each candidate is scored against every previewed Pokemon with the lead choice's matchup, and the tier
 * decides what it reads and how closely the draw follows the scores.
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
        return Scorer(LocalLeadChoice.temperature(tier)) { candidate ->
            val own = facts(candidate.speciesId, candidate.formId, candidate.level)?.takeIf { it.typeIds.isNotEmpty() }
                ?: return@Scorer null
            val moves = candidate.moveIds.mapNotNull(moveDetails)
            foes.map { foe -> LocalLeadChoice.edge(own.typeIds, own.stats, moves, foe.knownTypeIds, foe.combatStats, detailed) }
                .average()
        }
    }
}
