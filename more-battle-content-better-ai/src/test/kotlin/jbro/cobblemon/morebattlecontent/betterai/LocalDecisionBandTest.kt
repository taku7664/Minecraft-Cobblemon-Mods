package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionChoiceSeed
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the regret band opens up, and why it is the axis a difficulty tier rides on.
 *
 * Three levers were measured as difficulty settings and all three came back flat: the whole
 * foresight span is 51.4% +-3.8 over 180 battles, and a draw-sharpness multiplier spanning 0.35 to
 * 3.0 is 50.3% +-3.8. Only depth showed anything, at 55.1% +-3.7, and only on its first rung.
 *
 * Sharpness is the one that explains the rest. It was not inert - it swung how often the trainer
 * declined its top-ranked action from 44.1% of contested turns down to 17.6%, while leaving the
 * share of turns that offered no choice at all untouched at 43.3% - and it still won nothing. The
 * reading that survives is that the band admits only actions that are genuinely interchangeable.
 * That is the band working as designed, and it is also the reason no lever operating inside it can
 * ever be a difficulty: every one of them shuffles equally good answers.
 *
 * Which leaves the boundary itself. Widening the band is the one change that reaches actions the
 * shipped setting refuses outright, and it measured 57.1% +-3.8 for the narrow arm against an
 * eightfold band, monotone through 54.0% at fourfold. That is the ladder.
 */
class LocalDecisionBandTest {
    @Test
    fun `report what widening the regret band opens up`() {
        val rankings = rankPositions()
        val opened = LinkedHashMap<Double, Double>()
        val capped = LinkedHashMap<Double, Double>()
        println("REGRET BAND  positions=${rankings.size}")
        println("collapsed = the shortlist held one entry, so there was no choice to make.")
        println("chose-2nd = share of contested turns that did not take the top-ranked action.")
        println()
        println("%-10s %10s %12s %12s %10s".format("band", "collapsed", "shortlist", "chose-2nd", "count-cap"))
        BANDS.forEach { band ->
            var collapsed = 0
            var contested = 0
            var choseOther = 0
            var shortlistTotal = 0
            var cappedCount = 0
            rankings.forEach { (calculated, ranked) ->
                val selection = LocalWeightedActionSelector().choose(
                    ranked,
                    LocalActionChoiceSeed.derive(calculated.state.battleId, calculated.state.turn, ranked),
                    LocalActionMixingContext.balanced(0.5).copy(decisionRegretBand = band),
                )
                shortlistTotal += selection.shortlistSize
                // Whether the *count* limit is what stopped the shortlist, rather than the band.
                //
                // `shortlistSize` takes a fraction of the candidate count with a floor of two, so a
                // position with several live options can still be cut to two before the band is ever
                // consulted. Where that binds, widening the band cannot reach a third action however
                // far it is widened, and the weak end of the ladder is capped by the count instead.
                if (selection.shortlistSize >= LocalWeightedActionSelector()
                        .shortlistSize(ranked.size, LocalDecisionTuning.CURRENT) &&
                    ranked.size > selection.shortlistSize
                ) {
                    cappedCount++
                }
                if (selection.shortlistSize <= 1) {
                    collapsed++
                } else {
                    contested++
                    if (selection.rank !== ranked.first()) choseOther++
                }
            }
            opened[band] = collapsed * 100.0 / rankings.size
            capped[band] = cappedCount * 100.0 / rankings.size
            println(
                "%-10.2f %9.1f%% %12.2f %11.1f%% %9.1f%%".format(
                    band, opened.getValue(band), shortlistTotal.toDouble() / rankings.size,
                    if (contested == 0) 0.0 else choseOther * 100.0 / contested,
                    capped.getValue(band),
                ),
            )
        }
        println()
        println("A band that moves the collapse share is reaching actions the shipped setting")
        println("refuses outright, which is the only place a weaker tier can live.")
        println("count-cap is where the shortlist was stopped by the count limit and not the band.")
        println("Where that is high, widening further buys nothing and the count is the next lever.")

        // The shipped band is the strict one, and every widening has to stay strictly wider than it.
        // Asserted rather than only printed because the tier values now depend on this ordering: if a
        // future change to the shortlist or the tuning ceiling flattens the axis, the ladder silently
        // loses its only working rung and every tier goes back to playing the same battle.
        val shipped = opened.getValue(1.0)
        opened.filterKeys { it > 1.0 }.forEach { (band, collapse) ->
            assertTrue(
                collapse < shipped,
                "Band $band has to leave fewer positions without a choice than the shipped band, " +
                    "was $collapse against $shipped.",
            )
        }
    }

    /**
     * The count limit, asked at the band where it was shown to bind.
     *
     * At an eightfold band the shortlist is stopped by the count and not the band in 61.7% of
     * positions, so the weak end of the ladder is capped by how many alternatives it may hold rather
     * than by how bad they may be. This holds the band at that end and moves the width instead. If
     * the shortlist does not grow, the fraction is not the cap and the account of the flat bottom
     * rungs is wrong.
     */
    @Test
    fun `report what widening the shortlist count opens up`() {
        val rankings = rankPositions()
        val sizes = LinkedHashMap<Double, Double>()
        println("SHORTLIST WIDTH  positions=${rankings.size}  band held at $WEAK_END")
        println()
        println("%-10s %12s %12s %12s".format("width", "shortlist", "chose-2nd", "stopped-by-count"))
        WIDTHS.forEach { width ->
            var contested = 0
            var choseOther = 0
            var shortlistTotal = 0
            var cappedCount = 0
            rankings.forEach { (calculated, ranked) ->
                val selection = LocalWeightedActionSelector().choose(
                    ranked,
                    LocalActionChoiceSeed.derive(calculated.state.battleId, calculated.state.turn, ranked),
                    LocalActionMixingContext.balanced(0.5)
                        .copy(decisionRegretBand = WEAK_END, decisionShortlistWidth = width),
                )
                shortlistTotal += selection.shortlistSize
                if (selection.shortlistSize >= LocalWeightedActionSelector()
                        .shortlistSize(ranked.size, LocalDecisionTuning.CURRENT, width) &&
                    ranked.size > selection.shortlistSize
                ) {
                    cappedCount++
                }
                if (selection.shortlistSize > 1) {
                    contested++
                    if (selection.rank !== ranked.first()) choseOther++
                }
            }
            sizes[width] = shortlistTotal.toDouble() / rankings.size
            println(
                "%-10.2f %12.2f %11.1f%% %15.1f%%".format(
                    width, sizes.getValue(width),
                    if (contested == 0) 0.0 else choseOther * 100.0 / contested,
                    cappedCount * 100.0 / rankings.size,
                ),
            )
        }
        println()
        println("A width that does not grow the shortlist means the fraction was not the cap.")

        // The account of the flat bottom rungs rests entirely on this being the binding limit, so it
        // is asserted rather than read off a report nobody reruns.
        val shipped = sizes.getValue(1.0)
        assertTrue(
            sizes.getValue(WIDTHS.last()) > shipped,
            "Widening the shortlist fraction has to hold more alternatives than the shipped width, " +
                "was ${sizes.getValue(WIDTHS.last())} against $shipped.",
        )
    }

    private fun rankPositions(): List<Pair<BattleDecisionContext, List<LocalBattleActionRank>>> {
        val profile = BattleTrainerProfile(
            skillLevel = 3,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.ADVANCED.copy(lookaheadPlies = 2),
        )
        return recordPositions().map { context ->
            val calculated = PublicBattleTacticalCalculator.calculate(context)
            val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
            val lookahead = LocalRecursiveLookaheadEvaluator
                .evaluate(base, calculated, profile, LocalDecisionTuning.CURRENT)
            calculated to LocalRootDecisionPolicy.refine(lookahead.ranked, calculated).ranked
        }
    }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(12, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(120)
    }

    private companion object {
        const val SEED = 20260830
        val BANDS = listOf(1.0, 2.0, 4.0, 8.0)

        /** The widest band on the ladder, where the count was measured to be what binds. */
        const val WEAK_END = 8.0
        val WIDTHS = listOf(1.0, 1.5, 2.0, 3.0)
    }
}
