package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalPublicPositionFacts
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asks how many of the switches the AI actually plays have a reason behind them.
 *
 * "Switches that mean nothing" was one of the two symptoms this work started from, and it is the one
 * never looked at directly. The other - preferring resisted moves - turned out to be real and was
 * fixed; this one has only ever been guessed at.
 *
 * Switch scoring already carries a tempo penalty and requires a positioning gain, so on paper it
 * should refuse. But scoring is not the only path to a switch: the selector mixes, and a switch that
 * is not the best action can still be drawn. Whether that produces switches a player would call
 * pointless is a question about frequency, and frequency is measurable.
 *
 * Each switch the AI plays in self-play is classified by the reasons available to it at the time:
 * escaping a knockout threat, improving how much of its health the opponent removes per turn, or
 * bringing in something meaningfully healthier. A switch with none of those is one nothing in the
 * position argued for.
 */
class LocalSwitchJustificationTest {
    @Test
    fun `switches the AI plays are classified by the reason available for them`() {
        val contexts = recordPositions()
        val brain = LocalTacticalBrain()
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.STANDARD,
        )

        var switches = 0
        var escapingKnockout = 0
        var improvingExposure = 0
        var bringingHealth = 0
        var improvingOffence = 0
        var unjustified = 0
        var switchOptionsSeen = 0
        // Of the switches with no reason behind them, how many were the top-ranked action anyway.
        var unjustifiedAndTopRanked = 0
        var unjustifiedRankSum = 0
        // "Has a reason" and "a watcher would accept it" are different questions. The gains are
        // collected so the second one can be asked by size rather than by presence.
        val bestGains = mutableListOf<Double>()

        contexts.forEach { context ->
            val calculated = PublicBattleTacticalCalculator.calculate(context)
            if (calculated.candidates.none { it.kind == BattleActionKind.SWITCH }) return@forEach
            switchOptionsSeen++
            val session = brain.openSession(
                BattleBrainOpenContext(
                    battleId = calculated.state.battleId,
                    format = calculated.state.format,
                    trainerProfile = profile,
                    trainerPersonaId = "mbc:switch_probe",
                ),
            )
            val decision = brain.decide(session, calculated).toCompletableFuture().join()
            val chosen = calculated.candidates.firstOrNull { it.actionId == decision.actionId } ?: return@forEach
            if (chosen.kind != BattleActionKind.SWITCH) return@forEach

            switches++
            val active = LocalPublicPositionFacts.activeAlly(chosen, calculated)
            val target = LocalPublicPositionFacts.switchTarget(chosen, calculated)
            val tuning = LocalDecisionTuning.CURRENT
            val currentRisk = active?.let {
                LocalPublicPositionFacts.defensiveExposure(it, calculated, tuning = tuning)
            }
            val targetRisk = target?.let {
                LocalPublicPositionFacts.defensiveExposure(it, calculated, chosen.actorSlot, tuning)
            }
            val underThreat = active?.let {
                LocalPublicPositionFacts.isPublicKnockoutThreat(it, calculated, tuning)
            } == true
            val exposureBetter = currentRisk != null && targetRisk != null && targetRisk < currentRisk
            val healthier = active != null && target != null &&
                target.hpFraction - active.hpFraction >= HEALTH_MARGIN
            // The reason the first pass left out: a switch can be right because the incoming Pokemon
            // hits harder, not because the outgoing one was in danger.
            val offenceBetter = (
                LocalLookaheadStateEvaluator.switchOffensivePressureImprovement(chosen, calculated) ?: 0.0
                ) > OFFENCE_MARGIN

            // Board units: one is a full health bar. The largest single argument this switch had.
            bestGains += maxOf(
                (currentRisk ?: 0.0) - (targetRisk ?: 0.0),
                LocalLookaheadStateEvaluator.switchOffensivePressureImprovement(chosen, calculated) ?: 0.0,
                if (active != null && target != null) target.hpFraction - active.hpFraction else 0.0,
            )
            if (underThreat) escapingKnockout++
            if (exposureBetter) improvingExposure++
            if (healthier) bringingHealth++
            if (offenceBetter) improvingOffence++
            if (!underThreat && !exposureBetter && !healthier && !offenceBetter) {
                unjustified++
                // Where a reasonless switch came from decides where to fix it. Top-ranked means the
                // scoring model asked for it. Anywhere below means the scoring model ranked something
                // else first and the mixed draw reached past it.
                val ranked = LocalDecisionInstrumentation.inspect(calculated, profile)
                    .candidates.sortedByDescending { it.comparisonValue }
                val position = ranked.indexOfFirst { it.actionId == chosen.actionId }
                if (position == 0) unjustifiedAndTopRanked++
                if (position >= 0) unjustifiedRankSum += position + 1
            }
        }

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("SWITCH JUSTIFICATION  positions=${contexts.size}, of which $switchOptionsSeen offered a switch")
            appendLine("=".repeat(100))
            appendLine("Reasons are not exclusive; a switch can escape a threat and improve exposure at once.")
            appendLine()
            appendLine("  switches played                = $switches")
            appendLine("  escaping a knockout threat     = $escapingKnockout")
            appendLine("  improving defensive exposure   = $improvingExposure")
            appendLine("  bringing in something healthier= $bringingHealth")
            appendLine("  improving offensive pressure   = $improvingOffence")
            appendLine("  none of the above              = $unjustified")
            if (switches > 0) {
                appendLine()
                appendLine(
                    "  unjustified share = %.1f%%".format(unjustified * 100.0 / switches),
                )
            }
            if (unjustified > 0) {
                appendLine()
                appendLine("  of those, top-ranked by score = $unjustifiedAndTopRanked")
                appendLine("  mean rank when not            = %.1f".format(
                    unjustifiedRankSum.toDouble() / unjustified,
                ))
            }
            if (bestGains.isNotEmpty()) {
                appendLine()
                appendLine("-- size of the best argument each switch had, in board units --")
                listOf(
                    "negligible  (< 0.05)" to bestGains.count { it < 0.05 },
                    "slight      (< 0.15)" to bestGains.count { it in 0.05..0.15 },
                    "clear       (< 0.40)" to bestGains.count { it > 0.15 && it <= 0.40 },
                    "decisive    (>=0.40)" to bestGains.count { it > 0.40 },
                ).forEach { (label, count) ->
                    appendLine("  %-22s %d".format(label, count))
                }
                appendLine()
                appendLine("  median = %.3f".format(bestGains.sorted()[bestGains.size / 2]))
                appendLine()
                appendLine("A watcher does not check whether a gain was positive, only whether it looked")
                appendLine("worth a turn. Everything in the first two rows is a switch that had a reason")
                appendLine("too small to see.")
            }
            appendLine()
            appendLine("Top-ranked means the scoring model asked for the switch and the fix belongs there.")
            appendLine("Ranked below means the score preferred something else and the mixed draw reached")
            appendLine("past it, which is a different repair in a different file.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
    }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 30, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }
    }

    private companion object {
        const val BATTLES = 12
        const val SEED = 20260825
        const val HEALTH_MARGIN = 0.25
        const val OFFENCE_MARGIN = 0.02
    }
}
