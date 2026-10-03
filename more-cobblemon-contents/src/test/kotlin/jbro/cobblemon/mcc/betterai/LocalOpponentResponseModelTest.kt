package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.search.LocalOpponentResponseModel
import jbro.cobblemon.mcc.betterai.search.LocalSearchResponseObjective
import jbro.cobblemon.mcc.betterai.search.LocalOpponentResponseValue
import jbro.cobblemon.mcc.betterai.search.LocalResponseValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalOpponentResponseModelTest {
    @Test
    fun `public move switch tendencies weight both component slots of a double response`() {
        val turns = jointResponses()
        val distribution = requireNotNull(LocalOpponentResponseModel.distribution(turns, memory(8, 0.2, 0.8)))
        assertEquals(listOf(0.04, 0.16, 0.16, 0.64), turns.map { distribution.weights.getValue(it) }.map {
            kotlin.math.round(it * 100.0) / 100.0
        })
        assertEquals(BattlePredictedResponse.SWITCH, LocalOpponentResponseModel.responseKind(turns.last()))
        assertEquals(BattlePredictedResponse.OTHER, LocalOpponentResponseModel.responseKind(turns[1]))
    }

    @Test
    fun `a forced pass does not dilute the active partners observed switch tendency`() {
        val pass = BattleActionCandidate("pass", BattleActionKind.WAIT, actorSlot = 1)
        val turns = actions().map { joint(it, pass) }
        val distribution = requireNotNull(LocalOpponentResponseModel.distribution(turns, memory(8, 0.2, 0.8)))
        assertEquals(0.8, distribution.weights.getValue(turns.last()), 1e-9)
        assertEquals(0.2, turns.take(2).sumOf { distribution.weights.getValue(it) }, 1e-9)
    }

    @Test
    fun `the product response objective applies learned double switch behavior`() {
        val values = jointResponses().mapIndexed { index, action ->
            LocalOpponentResponseValue(action, LocalResponseValue(if (index == 3) 1.0 else -1.0, 1.0, 1.0))
        }
        val profile = BattleTrainerProfile.boss()
        val baseline = requireNotNull(LocalSearchResponseObjective.aggregate(values, BattleTacticalMemoryView.empty(),
            profile, setOf(BattleSituation.GENERAL)))
        val learned = requireNotNull(LocalSearchResponseObjective.aggregate(values, memory(8, 0.2, 0.8),
            profile, setOf(BattleSituation.GENERAL)))
        assertTrue(learned.value > baseline.value, "A learned two-switch response must affect product weighting")
    }

    @Test
    fun `fewer than three public samples cannot steer the response model`() {
        val memory = memory(samples = 2, moveRate = 0.2, switchRate = 0.8)

        assertNull(LocalOpponentResponseModel.distribution(actions(), memory))
    }

    @Test
    fun `one available response category provides no psychological information`() {
        val movesOnly = actions().filter { it.kind == BattleActionKind.USE_MOVE }

        assertNull(LocalOpponentResponseModel.distribution(movesOnly, memory(8, 0.2, 0.8)))
    }

    @Test
    fun `learned category probability is divided across actions instead of duplicated per move`() {
        val distribution = requireNotNull(
            LocalOpponentResponseModel.distribution(
                actions(),
                memory(samples = 8, moveRate = 0.2, switchRate = 0.8),
            ),
        )
        val moveMass = distribution.weights.entries
            .filter { it.key.kind == BattleActionKind.USE_MOVE }
            .sumOf { it.value }
        val switchMass = distribution.weights.entries
            .filter { it.key.kind == BattleActionKind.SWITCH }
            .sumOf { it.value }

        assertTrue(switchMass > moveMass)
        assertTrue(distribution.influence in 0.0..0.55)
    }

    @Test
    fun `repeated prediction misses reduce but do not invert learned influence`() {
        val steady = requireNotNull(
            LocalOpponentResponseModel.distribution(actions(), memory(8, 0.2, 0.8, consecutiveMisses = 0)),
        )
        val missing = requireNotNull(
            LocalOpponentResponseModel.distribution(actions(), memory(8, 0.2, 0.8, consecutiveMisses = 4)),
        )

        assertTrue(steady.influence > missing.influence)
        assertTrue(missing.influence >= 0.0)
    }

    @Test
    fun `a sampled current situation overrides the general response prior`() {
        val memory = BattleTacticalMemoryView(
            tendencies = tendencyPair(BattleSituation.GENERAL, 8, 0.8, 0.2) +
                tendencyPair(BattleSituation.UNDER_KO_THREAT, 6, 0.1, 0.9),
        )
        val distribution = requireNotNull(
            LocalOpponentResponseModel.distribution(
                actions(),
                memory,
                situations = setOf(BattleSituation.GENERAL, BattleSituation.UNDER_KO_THREAT),
            ),
        )
        val switchMass = distribution.weights.entries
            .filter { it.key.kind == BattleActionKind.SWITCH }
            .sumOf { it.value }

        assertTrue(switchMass > 0.5)
        assertTrue(distribution.situation == BattleSituation.UNDER_KO_THREAT)
    }

    @Test
    fun `recent behavior shift reduces reliance on a stale response prior`() {
        val steady = requireNotNull(LocalOpponentResponseModel.distribution(actions(), memory(8, 0.2, 0.8)))
        val shifting = requireNotNull(
            LocalOpponentResponseModel.distribution(
                actions(),
                memory(8, 0.2, 0.8, behaviorShiftEvidence = 1.0),
            ),
        )

        assertTrue(steady.influence > shifting.influence)
    }

    @Test
    fun `three situational samples shrink toward a well sampled general prior`() {
        val memory = BattleTacticalMemoryView(
            tendencies = tendencyPair(BattleSituation.GENERAL, 30, 0.9, 0.1) +
                tendencyPair(BattleSituation.UNDER_KO_THREAT, 3, 0.1, 0.9),
        )
        val distribution = requireNotNull(
            LocalOpponentResponseModel.distribution(
                actions(),
                memory,
                situations = setOf(BattleSituation.GENERAL, BattleSituation.UNDER_KO_THREAT),
            ),
        )
        val switchMass = distribution.weights.entries
            .filter { it.key.kind == BattleActionKind.SWITCH }
            .sumOf { it.value }

        assertTrue(switchMass in 0.1..0.5)
    }

    private fun jointResponses(): List<BattleActionCandidate> {
        val first = actions().filter { it.actionId != "move:b" }
        val second = listOf(
            BattleActionCandidate("move:partner", BattleActionKind.USE_MOVE, actorSlot = 1, moveSlot = 0, moveId = "a"),
            BattleActionCandidate("switch:partner", BattleActionKind.SWITCH, actorSlot = 1, switchPokemonId = UUID(0, 302)),
        )
        return first.flatMap { a -> second.map { b -> joint(a, b) } }
    }

    private fun joint(a: BattleActionCandidate, b: BattleActionCandidate) = BattleActionCandidate(
        "${a.actionId}+${b.actionId}", BattleActionKind.COMPOSITE,
        componentActionIds = listOf(a.actionId, b.actionId), componentActions = listOf(a, b))

    private fun actions(): List<BattleActionCandidate> = listOf(
        BattleActionCandidate("move:a", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "a"),
        BattleActionCandidate("move:b", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = "b"),
        BattleActionCandidate(
            "switch",
            BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = UUID.fromString("00000000-0000-0000-0000-000000000301"),
        ),
    )

    private fun memory(
        samples: Int,
        moveRate: Double,
        switchRate: Double,
        consecutiveMisses: Int = 0,
        behaviorShiftEvidence: Double = 0.0,
    ) = BattleTacticalMemoryView(
        tendencies = tendencyPair(BattleSituation.GENERAL, samples, moveRate, switchRate),
        predictionCalibration = BattlePredictionCalibrationView(
            samples = maxOf(samples, consecutiveMisses),
            hits = 0,
            consecutiveMisses = consecutiveMisses,
        ),
        opponentResponseVolatility = behaviorShiftEvidence,
    )

    private fun tendencyPair(
        situation: BattleSituation,
        samples: Int,
        moveRate: Double,
        switchRate: Double,
    ) = listOf(
        BattleTendencyView(situation, BattlePredictedResponse.MOVE, samples, moveRate * samples, moveRate),
        BattleTendencyView(situation, BattlePredictedResponse.SWITCH, samples, switchRate * samples, switchRate),
    )
}
