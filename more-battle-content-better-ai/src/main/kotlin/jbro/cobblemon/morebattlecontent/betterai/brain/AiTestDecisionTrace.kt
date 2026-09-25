package jbro.cobblemon.morebattlecontent.betterai.brain

import java.util.Locale
import org.slf4j.LoggerFactory
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank

/** Public-state decision trace for the reward-free /mbc test ai-* battle only. */
internal class AiTestDecisionTrace private constructor(
    private val context: BattleDecisionContext,
    private val startedAtNanos: Long,
) {
    private val battleId = context.state.battleId
    private val turn = context.state.turn

    fun begin() {
        val opponents = context.state.pokemon.asSequence().filter { it.side == BattleSide.OPPONENT }
            .joinToString(" | ") { pokemon ->
                val slots = context.publicActionCatalog.inferredMovesForPokemon(pokemon.battlePokemonId)
                    ?.slots.orEmpty().joinToString(",") { slot ->
                        "${slot.slot}:${slot.moveId ?: "?"}:${slot.knowledge}:${slot.source}"
                    }
                "${pokemon.speciesId}#${pokemon.battlePokemonId.toString().take(8)}" +
                    " active=${pokemon.activeSlot ?: "-"}" +
                    " publicMoves=${pokemon.knownMoveIds.sorted()}" +
                    " publicItem=${pokemon.knownHeldItemId ?: "?"}" +
                    " publicAbility=${pokemon.knownAbilityId ?: "?"}" +
                    " inferredSlots=[$slots]"
            }.ifEmpty { "none" }
        val inferences = context.state.inferences.asSequence()
            .filter { it.confidence in setOf(
                BattleInferenceConfidence.CONFIRMED,
                BattleInferenceConfidence.NEAR_CERTAIN,
                BattleInferenceConfidence.LIKELY,
            ) }
            .joinToString(",") {
                "${it.subjectPokemonId.toString().take(8)}:${it.categoryId}=${it.candidateId}:${it.confidence}"
            }.ifEmpty { "none" }
        logger.info("[BetterAI Trace] battle={} turn={} phase=knowledge opponents={} inferences={}",
            battleId, turn, opponents, inferences)
    }

    fun resolved(mode: String, ranked: List<LocalBattleActionRank>, selection: LocalActionSelection) {
        ranked.forEachIndexed { index, rank ->
            val candidate = rank.outcome.candidate
            val switchSpecies = candidate.switchPokemonId?.let { id ->
                context.state.pokemon.firstOrNull { it.battlePokemonId == id }?.speciesId ?: id.toString()
            }
            logger.info(
                "[BetterAI Trace] battle={} turn={} phase=candidate mode={} rank={} action={} kind={} move={} switch={} score={} base={} lookahead={} tier={} execution={}",
                battleId, turn, mode, index + 1, candidate.actionId, candidate.kind,
                candidate.moveId ?: "-", switchSpecies ?: "-", number(rank.comparisonValue),
                number(rank.outcome.tacticalUtility), number(rank.lookaheadUtility),
                rank.decisionTier, number(rank.executionProbability),
            )
        }
        logger.info(
            "[BetterAI Trace] battle={} turn={} phase=selected mode={} action={} move={} score={} probability={} shortlist={} elapsed_s={}",
            battleId, turn, mode, selection.rank.outcome.candidate.actionId,
            selection.rank.outcome.candidate.moveId ?: "-", number(selection.rank.comparisonValue),
            number(selection.probability), selection.shortlistSize, elapsedSeconds(),
        )
    }

    fun failed(status: String, issues: String) {
        logger.warn("[BetterAI Trace] battle={} turn={} phase=failed status={} issues={} elapsed_s={}",
            battleId, turn, status, issues, elapsedSeconds())
    }

    private fun elapsedSeconds(): String = number((System.nanoTime() - startedAtNanos) / 1_000_000_000.0)

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    companion object {
        private val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai/decision_trace")

        fun forTestPersona(
            personaId: String?,
            context: BattleDecisionContext,
            startedAtNanos: Long,
        ): AiTestDecisionTrace? =
            if (personaId?.startsWith(BattleBrainContentIds.AI_TEST_PERSONA_PREFIX) == true) {
                AiTestDecisionTrace(context, startedAtNanos).also(AiTestDecisionTrace::begin)
            } else null
    }
}
