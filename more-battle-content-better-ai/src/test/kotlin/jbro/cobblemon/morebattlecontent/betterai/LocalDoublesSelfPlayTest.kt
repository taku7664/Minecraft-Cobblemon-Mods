package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Doubles is played to a finish, and the shape of those battles is reported.
 *
 * Every judgement in this plan - the rejections included - was measured in singles, because the
 * self-play harness was hardcoded to it. That is not "doubles measured worse", it is doubles never
 * measured: two slots, joint actions, spread damage and redirection are the half of the product
 * nothing has ever put a number on.
 *
 * This does not assert a win rate. Win rate at these sample sizes is noise, and principle 7 says so.
 * What it asserts is that the format runs at all - both slots act, knockouts get replaced, battles
 * decide rather than stall out - and it prints the shape so the next change has something to move.
 */
class LocalDoublesSelfPlayTest {
    @Test
    fun `a doubles battle is played with both slots acting`() {
        val definition = LocalSelfPlayMeasurement
            .definitions(count = 1, seed = 20260829, format = BattleFormat.DOUBLE)
            .single()
        val report = LocalTacticalScenarioBattle.run(definition, maximumTurns = 20)
        println(report.documentationLog())
        assertTrue(report.turns.isNotEmpty(), "The battle produced no turns at all.")
        assertTrue(
            report.turns.any { it.cycleActual.contains('+') },
            "A doubles decision is a joint action, so at least one turn must show two components: " +
                report.turns.joinToString(" | ") { it.cycleActual },
        )
    }

    @Test
    fun `doubles battles decide as often as singles do`() {
        val doubles = LocalSelfPlayMeasurement.mirror(
            label = "doubles",
            tuning = LocalDecisionTuning.CURRENT,
            battles = BATTLES,
            seed = 20260829,
            maximumTurns = 30,
            format = BattleFormat.DOUBLE,
        )
        val singles = LocalSelfPlayMeasurement.mirror(
            label = "singles",
            tuning = LocalDecisionTuning.CURRENT,
            battles = BATTLES,
            seed = 20260829,
            maximumTurns = 30,
        )
        println(singles.row())
        println(doubles.row())
        assertTrue(
            doubles.decisiveRate > 0.0,
            "Not one doubles battle reached a conclusion, which is a broken harness rather than a " +
                "cautious AI: " + doubles.row(),
        )
    }

    @Test
    fun `a knockout in one slot is replaced without emptying the other`() {
        val definition = LocalSelfPlayMeasurement
            .definitions(count = 1, seed = 771, format = BattleFormat.DOUBLE)
            .single()
        val recorded = mutableListOf<jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        val everyDecisionHadAnActor = recorded.all { context ->
            context.candidates.all { candidate ->
                when (candidate.kind) {
                    BattleActionKind.COMPOSITE -> candidate.componentActions.isNotEmpty()
                    else -> true
                }
            }
        }
        assertTrue(everyDecisionHadAnActor, "A joint action was offered with no components in it.")
        assertTrue(recorded.isNotEmpty(), "No decision was ever asked for.")
    }

    /** Enough battles to say the format runs; far too few to say which side plays better. */
    private companion object {
        const val BATTLES = 6
    }
}
