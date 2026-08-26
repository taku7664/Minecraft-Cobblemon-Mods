package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerPersonality
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionChoiceSeed
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalHighestRankedActionSelector
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Measures the path a real battle actually takes.
 *
 * Every other tier measurement in this module injects [LocalHighestRankedActionSelector], which takes
 * the top-ranked action and stops. That is deliberate - it asks whether the *ranking* moves, with no
 * sampling noise in the answer - but it is not what ships. The Brain's default selector is
 * `LocalWeightedActionSelector`, which draws from a shortlist weighted by risk budget, trainer
 * personality and a persona-stable style, and which gates switches on their safety.
 *
 * So the machinery that makes one trainer differ from another has never appeared in a single number
 * this module has reported. These three measurements cover it:
 *
 *  1. the same positions decided by both selectors, side by side
 *  2. whether `riskTolerance` changes what gets played
 *  3. whether two different trainers play distinguishably
 *
 * None of this builds anything. It exists to find out what is already working before deciding what to
 * change, because the risk model turned out to be implemented rather than missing.
 */
class LocalProductionSelectorTest {
    @Test
    fun `the shipping selector is measured beside the deterministic one`() {
        val contexts = recordPositions()
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )
        val deterministic = tiers.associate { (name, profile) ->
            name to contexts.map { decide(it, profile, weighted = false) }
        }
        val shipping = tiers.associate { (name, profile) ->
            name to contexts.map { decide(it, profile, weighted = true) }
        }

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("SELECTOR COMPARISON  positions=${contexts.size}")
            appendLine("=".repeat(100))
            appendLine("deterministic = top-ranked action, what every earlier tier number measured")
            appendLine("shipping      = LocalWeightedActionSelector, what a player actually faces")
            appendLine()
            tiers.forEach { (name, _) ->
                val differing = contexts.indices.count {
                    deterministic.getValue(name)[it] != shipping.getValue(name)[it]
                }
                appendLine(
                    "  %-14s shipping differs from deterministic = %5.1f%% (%d/%d)".format(
                        name, differing * 100.0 / contexts.size, differing, contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("-- tier divergence under each selector --")
            listOf("deterministic" to deterministic, "shipping" to shipping).forEach { (label, results) ->
                val intro = results.getValue("introductory")
                val boss = results.getValue("boss")
                val extremes = contexts.indices.count { intro[it] != boss[it] }
                appendLine(
                    "  %-14s introductory vs boss = %5.1f%% (%d/%d)".format(
                        label, extremes * 100.0 / contexts.size, extremes, contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("A large gap between the two selectors means the shortlist draw, not the ranking,")
            appendLine("is deciding a real share of turns - and every earlier number understated what a")
            appendLine("player sees.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
    }

    @Test
    fun `risk tolerance is measured for whether it changes play`() {
        val contexts = recordPositions()
        val settings = listOf(0.2, 0.5, 0.8)
        val results = settings.associateWith { risk ->
            contexts.map { context ->
                val actionId = decide(context, BattleDifficultyProfiles.STANDARD, weighted = true, risk = risk)
                actionId to riskCharacter(context, actionId)
            }
        }

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("RISK TOLERANCE  positions=${contexts.size}  tier=standard")
            appendLine("=".repeat(100))
            appendLine("riskCharacter = accuracy risk + damage spread + recoil of the action played,")
            appendLine("the same quantity the selector tilts on. A cautious trainer should sit lower.")
            appendLine()
            val baseline = results.getValue(0.5)
            settings.forEach { risk ->
                val rows = results.getValue(risk)
                val differing = rows.indices.count { rows[it].first != baseline[it].first }
                appendLine(
                    "  riskTolerance=%.1f   mean riskCharacter=%.4f   differs from 0.5 = %5.1f%% (%d/%d)".format(
                        risk, rows.map { it.second }.average(),
                        differing * 100.0 / contexts.size, differing, contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("If the three rows play the same actions, the personality axis is inert and the")
            appendLine("trainer-character work has to start by making it bite.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
    }

    @Test
    fun `separate trainers are measured for whether they play differently`() {
        val contexts = recordPositions()
        val personas = listOf("mbc:rival_aria", "mbc:veteran_kestrel", "mbc:ace_dune", null)
        val results = personas.associateWith { persona ->
            contexts.map { decide(it, BattleDifficultyProfiles.STANDARD, weighted = true, persona = persona) }
        }

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("TRAINER IDENTITY  positions=${contexts.size}  tier=standard  personality=balanced")
            appendLine("=".repeat(100))
            appendLine("Same tier, same personality, same positions - only the persona id differs.")
            appendLine("LocalTrainerStyleModel derives a stable style from it (identity 80%, mood 20%),")
            appendLine("so any divergence here is trainer character rather than difficulty.")
            appendLine()
            val reference = results.getValue(personas.first())
            personas.drop(1).forEach { persona ->
                val rows = results.getValue(persona)
                val differing = rows.indices.count { rows[it] != reference[it] }
                appendLine(
                    "  %-22s differs from %s = %5.1f%% (%d/%d)".format(
                        persona ?: "(no persona)", personas.first(),
                        differing * 100.0 / contexts.size, differing, contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("Zero divergence means every trainer in the world plays identically, whatever the")
            appendLine("style model computes.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
    }

    @Test
    fun `the shortlist is measured for whether there is anything to choose between`() {
        val contexts = recordPositions()
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerPersonality.balanced(),
            difficulty = BattleDifficultyProfiles.STANDARD,
        )
        val settings = listOf(0.2, 0.5, 0.8)

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("SHORTLIST WIDTH  positions=${contexts.size}  tier=standard")
            appendLine("=".repeat(100))
            appendLine("Personality can only tilt a draw that has more than one entry. The selector")
            appendLine("returns immediately when the shortlist collapses to one, so a personality that")
            appendLine("changes nothing may mean the weights are wrong - or that there was never a")
            appendLine("choice to make.")
            appendLine()
            appendLine("The weight exponent runs 0.5..1.6, which is mild, so a peaked draw is unlikely")
            appendLine("to be the explanation. This separates the two.")
            appendLine()
            settings.forEach { risk ->
                val selections = contexts.map { context ->
                    val calculated = PublicBattleTacticalCalculator.calculate(context)
                    val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
                    val lookahead = LocalRecursiveLookaheadEvaluator
                        .evaluate(base, calculated, profile, LocalDecisionTuning.CURRENT)
                    val ranked = LocalRootDecisionPolicy.refine(lookahead.ranked, calculated).ranked
                    LocalWeightedActionSelector().choose(
                        ranked,
                        LocalActionChoiceSeed.derive(calculated.state.battleId, calculated.state.turn, ranked),
                        LocalActionMixingContext.balanced(risk),
                    ) to ranked.size
                }
                val collapsed = selections.count { it.first.shortlistSize <= 1 }
                val histogram = selections.groupingBy { it.first.shortlistSize }.eachCount().toSortedMap()
                    .entries.joinToString(" ") { "n${it.key}x${it.value}" }
                appendLine(
                    "  risk=%.1f  collapsed to one = %5.1f%% (%d/%d)  mean chosen probability=%.3f".format(
                        risk, collapsed * 100.0 / contexts.size, collapsed, contexts.size,
                        selections.map { it.first.probability }.average(),
                    ),
                )
                appendLine("            candidates/position mean=%.1f   shortlist sizes: %s".format(
                    selections.map { it.second.toDouble() }.average(), histogram,
                ))
            }
            appendLine()
            appendLine("collapsed high  -> the regret gap is too tight; personality has no room")
            appendLine("collapsed low but probability near 1.0 -> the weights are too peaked")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
    }

    /** The quantity `LocalWeightedActionSelector.riskMultiplier` tilts on, recomputed for reporting. */
    private fun riskCharacter(context: BattleDecisionContext, actionId: String): Double {
        val candidate = context.candidates.firstOrNull { it.actionId == actionId } ?: return 0.0
        val atomic = candidate.componentActions.ifEmpty { listOf(candidate) }
        return atomic.map { action: BattleActionCandidate ->
            val accuracyRisk = 1.0 - (
                action.facts?.baseAccuracyProbability ?: action.moveDetails?.accuracy?.div(100.0) ?: 1.0
                ).coerceIn(0.0, 1.0)
            val damageSpread = action.facts?.standardDamageFractionRange?.let { range ->
                (range.maximum - range.minimum).coerceIn(0.0, 1.0)
            } ?: 0.0
            val recoilRisk = action.facts?.selfRecoilFractionRange?.maximum ?: 0.0
            (accuracyRisk + damageSpread + recoilRisk).coerceIn(0.0, 1.0)
        }.average()
    }

    private fun decide(
        context: BattleDecisionContext,
        difficulty: BattleDifficultyProfile,
        weighted: Boolean,
        risk: Double = 0.5,
        persona: String? = "mbc:measurement",
    ): String {
        // The default constructor is the shipping configuration; the deterministic selector has to be
        // injected, which is exactly how it slipped into every earlier measurement unnoticed.
        val brain = if (weighted) LocalTacticalBrain() else LocalTacticalBrain(LocalHighestRankedActionSelector)
        val session = brain.openSession(
            BattleBrainOpenContext(
                battleId = context.state.battleId,
                format = context.state.format,
                trainerProfile = BattleTrainerProfile(
                    skillLevel = 2,
                    personality = BattleTrainerPersonality.balanced().copy(riskTolerance = risk),
                    difficulty = difficulty,
                ),
                trainerPersonaId = persona,
            ),
        )
        return brain.decide(session, context).toCompletableFuture().join().actionId
    }

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
    }
}
