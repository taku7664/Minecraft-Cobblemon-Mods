package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalHighestRankedActionSelector
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asks whether a difficulty tier changes what the AI does, rather than whether it wins more.
 *
 * Win rate is the wrong instrument for this question. Separating a ten point edge needs several
 * hundred battles per arm, and a Boss decision is allowed three seconds of search, so the ladder
 * cannot be run at a sample size that would resolve anything - the 24-battle duels come back at 52%,
 * 48% and 41%, all inside the noise band.
 *
 * Decision divergence is decisive at a sample size that is actually affordable. If two tiers pick the
 * same action in almost every position, no amount of self-play will separate them, and the tier is not
 * doing what its name promises. If they diverge often and still do not separate on results, that is a
 * different and more interesting problem.
 */
class LocalTierDivergenceTest {
    @Test
    fun `deeper tiers are measured for how often they decide differently`() {
        val contexts = recordPositions()
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )
        val resultsByTier = tiers.associate { (name, profile) ->
            name to contexts.map { context -> decide(context, profile) }
        }
        val choicesByTier = resultsByTier.mapValues { (_, results) -> results.map { it.actionId } }

        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("TIER DECISION DIVERGENCE  positions=${contexts.size}")
            appendLine("=".repeat(96))
            appendLine("Every tier re-decides the same recorded positions. Divergence is the share of")
            appendLine("positions where the deeper tier picks a different action than the one below it.")
            appendLine()
            tiers.zipWithNext { (lowerName, lower), (upperName, upper) ->
                val lowerChoices = choicesByTier.getValue(lowerName)
                val upperChoices = choicesByTier.getValue(upperName)
                val differing = lowerChoices.indices.count { lowerChoices[it] != upperChoices[it] }
                appendLine(
                    String.format(
                        "  %-14s (%dply) vs %-14s (%dply)   divergence=%5.1f%%  (%d/%d)",
                        upperName, upper.lookaheadPlies, lowerName, lower.lookaheadPlies,
                        differing * 100.0 / contexts.size, differing, contexts.size,
                    ),
                )
            }
            appendLine()
            val introChoices = choicesByTier.getValue("introductory")
            val bossChoices = choicesByTier.getValue("boss")
            val extremes = introChoices.indices.count { introChoices[it] != bossChoices[it] }
            appendLine(
                String.format(
                    "  %-14s (1ply) vs %-14s (4ply)   divergence=%5.1f%%  (%d/%d)",
                    "introductory", "boss", extremes * 100.0 / contexts.size, extremes, contexts.size,
                ),
            )
            appendLine()
            appendLine("-- search depth actually reached --")
            appendLine("A tier buys plies, but only a depth that finishes inside the time and node budget")
            appendLine("is accepted; a truncated one is discarded and the last completed depth is used.")
            appendLine("If the reached depth does not move with the tier, the extra allowance buys nothing.")
            tiers.forEach { (name, profile) ->
                val depths = resultsByTier.getValue(name).map { it.depthCompleted }
                val histogram = depths.groupingBy { it }.eachCount().toSortedMap()
                    .entries.joinToString(" ") { "d${it.key}x${it.value}" }
                appendLine(
                    String.format(
                        "  %-14s requested=%dply  reached: mean=%.2f  %s",
                        name, profile.lookaheadPlies, depths.average(), histogram,
                    ),
                )
            }
            appendLine()
            appendLine("-- can the search reach the decision at all --")
            appendLine("Depth is spent inside one term of the comparison. If that term is small next to")
            appendLine("the gap between the top two candidates, no amount of depth reorders anything.")
            val reach = contexts.map { context ->
                val breakdown = LocalDecisionInstrumentation.inspect(
                    context = context,
                    profile = BattleTrainerProfile(
                        skillLevel = 2,
                        personality = BattleTrainerProfile.champion().personality,
                        difficulty = BattleDifficultyProfiles.BOSS,
                    ),
                )
                val ranked = breakdown.candidates
                val gap = if (ranked.size < 2) 0.0 else ranked[0].comparisonValue - ranked[1].comparisonValue
                val swing = ranked.take(2).maxOfOrNull { kotlin.math.abs(it.lookaheadUtility) } ?: 0.0
                gap to swing
            }
            val meanGap = reach.map { it.first }.average()
            val meanSwing = reach.map { it.second }.average()
            val bridgeable = reach.count { (gap, swing) -> swing >= gap }
            appendLine(String.format("  mean top-two gap          = %8.1f", meanGap))
            appendLine(String.format("  mean lookahead swing      = %8.1f", meanSwing))
            appendLine(
                String.format(
                    "  positions the search could flip = %d/%d (%.1f%%)",
                    bridgeable, contexts.size, bridgeable * 100.0 / contexts.size,
                ),
            )
            appendLine()
            appendLine("Near-zero divergence between the extremes would mean the difficulty setting is")
            appendLine("cosmetic, whatever the win rates say.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
        assertTrue(choicesByTier.values.all { it.size == contexts.size }, report)
    }

    /** Real positions from played battles, not synthesised ones. */
    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(
                definition,
                maximumTurns = 20,
                recordedContexts = recorded,
            )
        }
        return recorded.filter { it.candidates.size > 1 }.take(POSITION_LIMIT)
    }

    private data class TierDecision(val actionId: String, val depthCompleted: Int)

    private fun decide(context: BattleDecisionContext, difficulty: BattleDifficultyProfile): TierDecision {
        val brain = LocalTacticalBrain(LocalHighestRankedActionSelector)
        val session = brain.openSession(
            BattleBrainOpenContext(
                context.state.battleId,
                context.state.format,
                trainerProfile = BattleTrainerProfile(
                    skillLevel = 2,
                    personality = BattleTrainerProfile.champion().personality,
                    difficulty = difficulty,
                ),
            ),
        )
        val decision = brain.decide(session, context).toCompletableFuture().join()
        // The Brain already publishes the depth it managed to complete; reading the tag costs nothing,
        // whereas re-running the search to observe it would double an already expensive measurement.
        val depth = decision.tags.firstOrNull { it.startsWith("lookahead_turns_") }
            ?.removePrefix("lookahead_turns_")?.toIntOrNull() ?: -1
        return TierDecision(decision.actionId, depth)
    }

    private companion object {
        const val BATTLES = 4
        /** Boss re-decides every one of these at up to three seconds, so keep the set small. */
        const val POSITION_LIMIT = 40
        const val SEED = 20260825
    }
}
