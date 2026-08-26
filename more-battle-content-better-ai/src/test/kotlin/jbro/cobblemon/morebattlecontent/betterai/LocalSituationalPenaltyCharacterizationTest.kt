package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins what the situational penalties currently do, before the root comparison is rewritten.
 *
 * These ten penalties are the part of the immediate heuristic that is not a value judgement. They
 * are statements about the *candidate* - this move repeats Protect, this move's stat boost is already
 * saturated, this move's public requirement is not met - which is why they cannot move into the leaf
 * evaluator: the leaf scores a state and never sees a candidate. When the search takes over the value
 * half of the comparison, these have to survive unchanged at the root.
 *
 * Only one of the ten is named anywhere in the test suite today, so there is nothing to notice if a
 * rewrite quietly drops one. This is a characterization test, not a specification: it records what
 * they do on a fixed set of real positions so that a change shows up as a diff rather than as a bug
 * reported months later. A failure here is a question - "did you mean to change this?" - not a defect.
 */
class LocalSituationalPenaltyCharacterizationTest {
    @Test
    fun `situational penalties are recorded across real positions`() {
        val contexts = recordPositions()
        val counts = PENALTIES.associate { (name, penalty) ->
            name to contexts.sumOf { context ->
                calculated(context).candidates.count { candidate -> penalty(candidate, context) > 0.0 }
            }
        }
        val decisive = PENALTIES.associate { (name, penalty) ->
            name to contexts.count { context -> isDecisive(context, penalty) }
        }

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("SITUATIONAL PENALTY CHARACTERIZATION  positions=${contexts.size}")
            appendLine("=".repeat(100))
            appendLine("applied  = candidate/position pairs where the penalty is non-zero")
            appendLine("decisive = positions where the top-scoring candidate would change if this")
            appendLine("           penalty alone were removed")
            appendLine()
            appendLine("  %-38s %8s %9s".format("penalty", "applied", "decisive"))
            PENALTIES.forEach { (name, _) ->
                appendLine("  %-38s %8d %9d".format(name, counts.getValue(name), decisive.getValue(name)))
            }
            appendLine()
            appendLine("A penalty that is never applied on this fixture is not proven dead - it may need a")
            appendLine("position this fixture does not produce. It is proven unmeasured, which is the")
            appendLine("useful thing to know before a rewrite.")
        }
        println(report)

        // The fixture has to keep producing positions, or this test silently measures nothing.
        assertTrue(contexts.size >= 20, report)
        // `applied` depends only on the candidate and the current board, never on how anything is
        // scored, so it survives a scoring rewrite unchanged and is safe to pin. `decisive` is
        // deliberately reported and not asserted: it is a function of the comparison itself, so
        // rewriting the comparison is supposed to move it. Pinning it would manufacture a failure
        // that could only ever be answered by updating the number.
        assertEquals(EXPECTED_APPLIED, counts.filterValues { it > 0 }, report)
    }

    /** Whether dropping this one penalty would change which candidate scores highest. */
    private fun isDecisive(
        context: BattleDecisionContext,
        penalty: (BattleActionCandidate, BattleDecisionContext) -> Double,
    ): Boolean {
        val breakdown = LocalDecisionInstrumentation.inspect(context = context, profile = PROFILE)
        val withPenalty = breakdown.candidates.maxByOrNull { it.comparisonValue }?.actionId
        val without = breakdown.candidates.maxByOrNull { candidate ->
            val original = calculated(context).candidates.firstOrNull { it.actionId == candidate.actionId }
            candidate.comparisonValue + (original?.let { penalty(it, context) } ?: 0.0)
        }?.actionId
        return withPenalty != without
    }

    private val calculatedCache = mutableMapOf<BattleDecisionContext, BattleDecisionContext>()

    private fun calculated(context: BattleDecisionContext): BattleDecisionContext =
        calculatedCache.getOrPut(context) { PublicBattleTacticalCalculator.calculate(context) }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(POSITION_LIMIT)
    }

    private companion object {
        const val BATTLES = 4
        const val POSITION_LIMIT = 40
        const val SEED = 20260825

        val PROFILE = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )

        val PENALTIES: List<Pair<String, (BattleActionCandidate, BattleDecisionContext) -> Double>> = listOf(
            "activePersistentEffectRefreshPenalty" to
                LocalTacticalSituationalEvaluator::activePersistentEffectRefreshPenalty,
            "expiredFirstActiveTurnPenalty" to
                LocalTacticalSituationalEvaluator::expiredFirstActiveTurnPenalty,
            "saturatedStatStagePenalty" to LocalTacticalSituationalEvaluator::saturatedStatStagePenalty,
            "unmetPublicRequirementPenalty" to
                LocalTacticalSituationalEvaluator::unmetPublicRequirementPenalty,
            "recentPublicFailurePenalty" to LocalTacticalSituationalEvaluator::recentPublicFailurePenalty,
            "repeatedProtectionPenalty" to LocalTacticalSituationalEvaluator::repeatedProtectionPenalty,
            "pendingDamagingMoveRiskPenalty" to
                LocalTacticalSituationalEvaluator::pendingDamagingMoveRiskPenalty,
            "consecutiveUseForbiddenPenalty" to
                LocalTacticalSituationalEvaluator::consecutiveUseForbiddenPenalty,
            "forcedTempoPenalty" to LocalTacticalSituationalEvaluator::forcedTempoPenalty,
        )

        /**
         * Measured, not chosen. Only penalties that actually fire on this fixture are pinned -
         * asserting zero for the rest would pin the fixture's gaps rather than the AI's behaviour.
         *
         * Eight of the nine never fire here. That is the finding, and it is worth more than the pin:
         * the self-play fixture never reaches a saturated stat stage, an unmet public requirement, a
         * repeated Protect, or a forced tempo turn, so none of those guards is under measurement at
         * all. They are not proven dead - they are proven unwatched, and a rewrite that broke one
         * would produce a green suite.
         *
         * They are nonetheless low risk for the search-led rewrite specifically, because that rewrite
         * replaces the value half of the comparison and leaves these terms untouched. The risk it does
         * carry is calibration: these magnitudes were tuned against heuristic-scale scores, and a
         * search-scale score may make them negligible or overwhelming. `decisive` in the report is the
         * tripwire for that, which is why it is printed on every run.
         */
        val EXPECTED_APPLIED: Map<String, Int> = mapOf(
            "pendingDamagingMoveRiskPenalty" to 4,
        )
    }
}
