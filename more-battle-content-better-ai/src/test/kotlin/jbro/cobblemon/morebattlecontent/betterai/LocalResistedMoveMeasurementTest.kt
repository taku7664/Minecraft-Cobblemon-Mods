package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Measures the reported symptom directly: how often the AI picks a resisted move over an available
 * super-effective one.
 *
 * The self-play harness cannot see this. Every Pokemon there has public combat stats and a single
 * opponent, so the Showdown projection succeeds for every candidate and the raw-power fallback never
 * runs. In a real battle the projection drops out routinely - a battle mechanic is attached, the
 * target pattern is a spread move, or a double battle leaves the target unresolved - and only then do
 * the two scales end up in the same comparison.
 *
 * So this builds the position where the bug actually lives: some candidates carry a projection, others
 * do not, and the AI has to rank them against each other.
 */
class LocalResistedMoveMeasurementTest {
    private val simulation = LocalTacticalBrainSimulationTest()

    @Test
    fun `type effectiveness ranking is measured on the unprojected path`() {
        LocalTypeEffectivenessProbe.verifyMatchups()
        val cases = LocalTypeEffectivenessProbe.cases(Random(SEED), CASES)
        val legacy = LocalTypeEffectivenessProbe.run(simulation, cases, LocalDecisionTuning.LEGACY)
        val current = LocalTypeEffectivenessProbe.run(simulation, cases, LocalDecisionTuning.CURRENT)

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("RESISTED-MOVE MEASUREMENT  seed=$SEED cases=$CASES")
            appendLine("=".repeat(104))
            appendLine("Each case offers one super-effective move and one resisted move of higher raw power.")
            appendLine("The resisted move is the one whose Showdown projection is unavailable, which is what")
            appendLine("puts raw power and HP-fraction pressure into the same comparison.")
            appendLine()
            appendLine(legacy.row())
            appendLine(current.row())
            appendLine()
            appendLine("resisted-pick rate = share of cases where the AI chose the resisted move")
            appendLine()
            appendLine("-- cases the fixed tuning still gets wrong --")
            cases.asSequence()
                .filter { LocalTypeEffectivenessProbe.picksResisted(simulation, it, LocalDecisionTuning.CURRENT) }
                .distinctBy { it.attackingTypeResisted + it.defenderTypes.joinToString() }
                .take(4)
                .forEach { case ->
                    appendLine(
                        "  ${case.attackingTypeSuperEffective}(${case.superEffectivePower}) vs " +
                            "${case.attackingTypeResisted}(${case.resistedPower}) into ${case.defenderTypes}",
                    )
                    append(
                        LocalTypeEffectivenessProbe
                            .breakdown(simulation, case, LocalDecisionTuning.CURRENT)
                            .format("current"),
                    )
                }
        }
        println(report)

        assertTrue(current.resistedPickRate <= legacy.resistedPickRate, report)
        assertTrue(cases.size == CASES, report)
    }

    private companion object {
        const val CASES = 200
        const val SEED = 20260825
    }
}

internal data class LocalTypeEffectivenessTally(
    val label: String,
    val cases: Int,
    val resistedPicks: Int,
) {
    val resistedPickRate: Double get() = if (cases == 0) 0.0 else resistedPicks.toDouble() / cases

    fun row(): String = String.format(
        "%-22s cases=%-5d resisted-pick rate=%6.1f%%  (%d/%d)",
        label, cases, resistedPickRate * 100, resistedPicks, cases,
    )
}

internal data class LocalTypeEffectivenessCase(
    val attackingTypeSuperEffective: String,
    val attackingTypeResisted: String,
    val defenderTypes: Set<String>,
    val superEffectivePower: Double,
    val resistedPower: Double,
)

internal object LocalTypeEffectivenessProbe {
    /**
     * Pairs where one attacking type is doubly effective and another is resisted against the same
     * defender, so the correct answer is unambiguous from public type data alone.
     */
    private val MATCHUPS = listOf(
        Triple("water", "fire", setOf("rock", "ground")),
        Triple("electric", "fighting", setOf("water", "flying")),
        Triple("ground", "normal", setOf("steel", "rock")),
        Triple("ice", "water", setOf("dragon", "flying")),
        Triple("fighting", "normal", setOf("rock", "steel")),
        Triple("fire", "grass", setOf("bug", "steel")),
        Triple("psychic", "fighting", setOf("poison", "fighting")),
        Triple("flying", "ground", setOf("grass", "fighting")),
    )

    /**
     * Fails loudly if a matchup does not actually pose the question it claims to.
     *
     * The first version of this table listed `grass into water/flying` as resisted; it is `2.0 x 0.5`,
     * a clean neutral hit, so preferring the 120 BP neutral move over a 75 BP super-effective one was
     * correct play being counted as a miss. A measurement that can quietly disagree with itself is
     * worse than no measurement, so the premise is checked rather than assumed.
     */
    fun verifyMatchups() {
        MATCHUPS.forEach { (strong, weak, defender) ->
            val strongMultiplier = StandardTypeEffectiveness.multiplier(strong, defender)
            val weakMultiplier = StandardTypeEffectiveness.multiplier(weak, defender)
            check(strongMultiplier >= 2.0) {
                "$strong into $defender is ${strongMultiplier}x, not super effective"
            }
            check(weakMultiplier <= 0.5) {
                "$weak into $defender is ${weakMultiplier}x, not resisted"
            }
        }
    }

    fun cases(random: Random, count: Int): List<LocalTypeEffectivenessCase> = List(count) {
        val (strong, weak, defender) = MATCHUPS[random.nextInt(MATCHUPS.size)]
        LocalTypeEffectivenessCase(
            attackingTypeSuperEffective = strong,
            attackingTypeResisted = weak,
            defenderTypes = defender,
            // The super-effective option is the weaker move on paper. That is the whole point: a
            // correct ranking has to prefer it anyway, because effectiveness beats base power here.
            superEffectivePower = 60.0 + random.nextInt(5) * 5.0,
            resistedPower = 110.0 + random.nextInt(5) * 5.0,
        )
    }

    fun run(
        simulation: LocalTacticalBrainSimulationTest,
        cases: List<LocalTypeEffectivenessCase>,
        tuning: LocalDecisionTuning,
    ): LocalTypeEffectivenessTally {
        var resistedPicks = 0
        cases.forEach { case ->
            val chosen = choose(simulation, case, tuning)
            if (chosen == "resisted") resistedPicks++
        }
        return LocalTypeEffectivenessTally(
            label = if (tuning.legacyRawPowerFallback) "legacy (pre-fix)" else "current (fixed)",
            cases = cases.size,
            resistedPicks = resistedPicks,
        )
    }

    fun picksResisted(
        simulation: LocalTacticalBrainSimulationTest,
        case: LocalTypeEffectivenessCase,
        tuning: LocalDecisionTuning,
    ): Boolean = choose(simulation, case, tuning) == "resisted"

    fun breakdown(
        simulation: LocalTacticalBrainSimulationTest,
        case: LocalTypeEffectivenessCase,
        tuning: LocalDecisionTuning,
    ): LocalDecisionBreakdown = LocalDecisionInstrumentation.inspect(
        context = contextFor(simulation, case),
        profile = BattleTrainerProfile.balanced(),
        tuning = tuning,
    )

    private fun contextFor(
        simulation: LocalTacticalBrainSimulationTest,
        case: LocalTypeEffectivenessCase,
    ) = simulation.contextOf(
        candidates = listOf(
            simulation.move(
                id = "super_effective",
                power = case.superEffectivePower,
                typeId = case.attackingTypeSuperEffective,
                facts = simulation.damageFacts(0.45),
            ),
            simulation.move(
                id = "resisted",
                power = case.resistedPower,
                typeId = case.attackingTypeResisted,
                facts = null,
            ),
        ),
        turn = 3,
        allyTypes = setOf("normal"),
        opponentTypes = case.defenderTypes,
    )

    private fun choose(
        simulation: LocalTacticalBrainSimulationTest,
        case: LocalTypeEffectivenessCase,
        tuning: LocalDecisionTuning,
    ): String {
        val candidates = listOf<BattleActionCandidate>(
            // Super-effective, lower power, and projectable.
            simulation.move(
                id = "super_effective",
                power = case.superEffectivePower,
                typeId = case.attackingTypeSuperEffective,
                facts = simulation.damageFacts(0.45),
            ),
            // Resisted, higher power, and deliberately left without a damage projection so it takes
            // the fallback branch - the production shape being measured.
            simulation.move(
                id = "resisted",
                power = case.resistedPower,
                typeId = case.attackingTypeResisted,
                facts = null,
            ),
        )
        val context = simulation.contextOf(
            candidates = candidates,
            turn = 3,
            allyTypes = setOf("normal"),
            opponentTypes = case.defenderTypes,
        )
        val breakdown = LocalDecisionInstrumentation.inspect(
            context = context,
            profile = BattleTrainerProfile.balanced(),
            tuning = tuning,
        )
        return breakdown.chosenByRanking?.actionId ?: "none"
    }
}
