package jbro.cobblemon.morebattlecontent.betterai.brain

import java.util.Locale
import org.slf4j.LoggerFactory
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadEvaluation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation

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
                        "${slot.slot}:${slot.moveId ?: "?"}:${slot.group}:${slot.knowledge}:${slot.source}"
                    }
                "${pokemon.speciesId}#${pokemon.battlePokemonId.toString().take(8)}" +
                    " active=${pokemon.activeSlot ?: "-"}" +
                    " hp=${number(pokemon.hpFraction)} status=${pokemon.statusId ?: "-"}" +
                    " types=${pokemon.knownTypeIds.sorted()}" +
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
        val confirmed = context.state.pokemon.asSequence().filter { it.side == BattleSide.OPPONENT }
            .joinToString(" | ") { pokemon ->
                val tierReadMoves = context.publicActionCatalog.inferredMovesForPokemon(pokemon.battlePokemonId)
                    ?.slots.orEmpty().filter { it.source == BattleOpponentMoveSource.DIFFICULTY_SET_READ }
                    .mapNotNull { it.moveId }
                "${pokemon.battlePokemonId.toString().take(8)}:" +
                    "revealedMoves=${pokemon.knownMoveIds.sorted()}:" +
                    "tierReadMoves=$tierReadMoves:" +
                    "revealedItem=${pokemon.knownHeldItemId ?: "-"}:" +
                    "revealedAbility=${pokemon.knownAbilityId ?: "-"}"
            }.ifEmpty { "none" }
        logger.info("[BetterAI Trace] battle={} turn={} phase=confirmed evidence={}", battleId, turn, confirmed)
        val recentEvents = context.state.observedEvents.takeLast(8)
            .joinToString(" | ") { event ->
                "${event.sequence}:${event.kind}:${event.actorPokemonId?.toString()?.take(8) ?: "-"}" +
                    ":${event.publicValueId ?: "-"}"
            }.ifEmpty { "none" }
        logger.info("[BetterAI Trace] battle={} turn={} phase=events recent={}", battleId, turn, recentEvents)
    }

    fun nativeSearch(result: NativeInitialProductDecisionEvaluation, requestedDepth: Int, budget: LocalLookaheadBudget) {
        logger.info(
            "[BetterAI Trace] battle={} turn={} phase=search engine=native status={} search={} requestedDepth={} completedDepth={} nodes={} nodeLimit={} timeLimitMs={} truncated={} worlds={} failedWorld={} failedRun={} detail={} elapsed_s={}",
            battleId, turn, result.status, result.searchStatus ?: "-", requestedDepth,
            result.depthCompleted, result.nodesVisited, budget.nodeLimit, budget.timeMillis,
            result.truncated, result.sessionState?.worlds?.size ?: 0,
            result.failedWorldId ?: "-", result.failedRunStatus ?: "-", result.failedRunDetail ?: "-",
            elapsedSeconds(),
        )
    }

    fun legacySearch(result: LocalLookaheadEvaluation, requestedDepth: Int, budget: LocalLookaheadBudget) {
        logger.info(
            "[BetterAI Trace] battle={} turn={} phase=search engine=legacy stop={} requestedDepth={} completedDepth={} nodes={} nodeLimit={} timeLimitMs={} pruned={} responseCoverage={} truncated={} elapsedMs={}",
            battleId, turn, result.terminationReason, requestedDepth, result.depthCompleted,
            result.nodesVisited, budget.nodeLimit, budget.timeMillis, result.branchesPruned,
            number(result.publicResponseCoverage), result.truncated, result.elapsedMillis,
        )
    }

    fun resolved(mode: String, ranked: List<LocalBattleActionRank>, selection: LocalActionSelection) {
        ranked.forEachIndexed { index, rank ->
            val candidate = rank.outcome.candidate
            val drawProbability = selection.probabilitiesByActionId[candidate.actionId]
                ?.let(::number) ?: if (selection.probabilitiesByActionId.isEmpty()) "?" else "0.000"
            val switchSpecies = candidate.switchPokemonId?.let { id ->
                context.state.pokemon.firstOrNull { it.battlePokemonId == id }?.speciesId ?: id.toString()
            }
            val moveDetails = candidate.moveDetails?.let { "${it.typeId}/${number(it.power)}" } ?: "?"
            val damage = candidate.facts?.standardDamageFractionRange?.let {
                "${number(it.minimum)}..${number(it.maximum)}"
            } ?: "?"
            logger.info(
                "[BetterAI Trace] battle={} turn={} phase=candidate mode={} rank={} action={} kind={} actor={} move={} moveSlot={} targets={} components={} details={} mechanic={} switch={} score={} gap={} base={} lookahead={} tier={} execution={} drawProbability={} typeMultiplier={} damageFraction={}",
                battleId, turn, mode, index + 1, candidate.actionId, candidate.kind,
                candidate.actorSlot ?: "-", candidate.moveId ?: "-", candidate.moveSlot ?: "-",
                candidate.targets, candidate.componentActionIds, moveDetails,
                candidate.mechanic?.mechanicId ?: "-",
                switchSpecies ?: "-", number(rank.comparisonValue),
                number(ranked.first().comparisonValue - rank.comparisonValue),
                number(rank.outcome.tacticalUtility), number(rank.lookaheadUtility),
                rank.decisionTier, number(rank.executionProbability), drawProbability,
                candidate.facts?.typeChartMultiplier?.let(::number) ?: "?", damage,
            )
        }
        logger.info(
            "[BetterAI Trace] battle={} turn={} phase=selected mode={} action={} move={} rank={} score={} probability={} shortlist={} candidates={} seed={} elapsed_s={}",
            battleId, turn, mode, selection.rank.outcome.candidate.actionId,
            selection.rank.outcome.candidate.moveId ?: "-",
            ranked.indexOfFirst { it.outcome.candidate.actionId == selection.rank.outcome.candidate.actionId } + 1,
            number(selection.rank.comparisonValue), number(selection.probability),
            selection.shortlistSize, ranked.size, selection.seed.toULong().toString(16), elapsedSeconds(),
        )
    }

    fun nativeFallback(status: String, issues: String) {
        logger.warn("[BetterAI Trace] battle={} turn={} phase=native_fallback status={} issues={} next=legacy_lookahead elapsed_s={}",
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
