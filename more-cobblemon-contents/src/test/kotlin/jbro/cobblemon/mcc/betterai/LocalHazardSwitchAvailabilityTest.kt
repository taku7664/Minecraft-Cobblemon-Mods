package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalIdleUtilityMoveRules
import jbro.cobblemon.mcc.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalHazardSwitchAvailabilityTest {
    @Test
    fun `singles hazards use the full participating team reserve capacity`() {
        for (size in 2..6) for (remaining in 1..size) {
            assertHazards(BattleFormat.SINGLE, size, remaining, (remaining - 1).toDouble() / (size - 1))
        }
    }

    @Test
    fun `doubles hazards subtract both active slots from reserve capacity`() {
        for (size in 2..6) for (remaining in 1..size) {
            val expected = if (size == 2) 0.0 else (remaining - 2).coerceAtLeast(0).toDouble() / (size - 2)
            assertHazards(BattleFormat.DOUBLE, size, remaining, expected)
        }
    }

    @Test
    fun `six preview choices selecting three use three participants`() {
        assertHazards(BattleFormat.SINGLE, 3, 2, 0.5, previewSize = 6)
        assertHazards(BattleFormat.DOUBLE, 3, 3, 1.0, previewSize = 6)
    }

    @Test
    fun `public remaining and fainted counts preserve capacity without a preview`() {
        assertHazards(BattleFormat.SINGLE, 6, 3, 0.4, previewSize = null, hideLiveReserves = true)
        assertHazards(BattleFormat.DOUBLE, 4, 3, 0.5, previewSize = null, hideLiveReserves = true)
    }

    @Test
    fun `last opponent makes hazard moves idle`() {
        val source = context(BattleFormat.SINGLE, 6, 1, "toxicspikes")
        assertTrue(LocalIdleUtilityMoveRules.isIdle(source.candidates.single(), source))
        assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(source.candidates.single(), source, 1.0))
    }

    @Test
    fun `own hazards get the same reserve discount while screens retain their old value`() {
        val source = context(BattleFormat.DOUBLE, 4, 3, "spikes")
        val own = source.state.derive(remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 4),
            pokemon = roster(BattleSide.ALLY, BattleFormat.DOUBLE, 4, 3) + roster(BattleSide.OPPONENT, BattleFormat.DOUBLE, 4, 4))
        assertEquals(-0.05, LocalImmediateTurnScorer.score(own, withEffect(own, BattleSide.ALLY, "spikes"), source).fieldDelta, 1e-12)
        assertEquals(0.15, LocalImmediateTurnScorer.score(own, withEffect(own, BattleSide.ALLY, "reflect"), source).fieldDelta, 1e-12)
    }

    private fun assertHazards(format: BattleFormat, size: Int, remaining: Int, expected: Double,
        previewSize: Int? = size, hideLiveReserves: Boolean = false) {
        for (hazard in listOf("stealthrock", "spikes", "toxicspikes", "stickyweb")) {
            val source = context(format, size, remaining, hazard, previewSize, hideLiveReserves)
            val candidate = source.candidates.single()
            assertEquals(20.0 * expected, LocalNonDamagingMoveEvaluator.pressure(candidate, source, 1.0), 1e-12,
                "$format $remaining/$size $hazard root")
            val after = withEffect(source.state, BattleSide.OPPONENT, hazard)
            assertEquals(0.1 * expected, LocalImmediateTurnScorer.score(source.state, after, source).fieldDelta, 1e-12,
                "$format $remaining/$size $hazard projected turn")
            assertEquals(0.1 * expected, LocalImmediateTurnScorer.positionEffectValue(after), 1e-12,
                "$format $remaining/$size $hazard native leaf")
        }
    }

    private fun context(format: BattleFormat, size: Int, remaining: Int, hazard: String,
        previewSize: Int? = size, hideLiveReserves: Boolean = false): BattleDecisionContext {
        val state = BattleStateView(UUID.randomUUID(), format, 1,
            roster(BattleSide.ALLY, format, size, size) + roster(BattleSide.OPPONENT, format, size, remaining, hideLiveReserves),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to size, BattleSide.OPPONENT to remaining), emptyList(), emptyList())
        val move = BattleActionCandidate(hazard, BattleActionKind.USE_MOVE, 0, 0, "cobblemon:$hazard",
            moveDetails = BattleMoveCandidateView("poison", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
                BattleMoveTargetPattern.SIDE, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(BattleMoveEffectView(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.TARGET_SIDE,
                        probability = 1.0, valueId = hazard)), false)))
        val preview = previewSize?.let { total -> BattleOpponentTeamPreviewView(size,
            List(total) { BattleOpponentTeamPreviewPokemonView(it, "cobblemon:pikachu", null, 50) }) }
        return BattleDecisionContext(UUID.randomUUID(), state, listOf(move), Long.MAX_VALUE).copy(opponentTeamPreview = preview)
    }

    private fun roster(side: BattleSide, format: BattleFormat, size: Int, remaining: Int, hideLiveReserves: Boolean = false): List<BattlePokemonStateView> =
        (0 until size).filter { !hideLiveReserves || it < slots(format) || it >= remaining }.map { index ->
            val fainted = index >= remaining
            BattlePokemonStateView(UUID.nameUUIDFromBytes("$side:$index".toByteArray()), side,
                if (!fainted && index < slots(format)) index else null, "cobblemon:pikachu", null, 50,
                if (fainted) 0.0 else 1.0, null, emptyMap(), emptySet(), null, null, fainted)
        }

    private fun withEffect(state: BattleStateView, side: BattleSide, effect: String) = state.derive(field = BattleFieldStateView(
        null, null, emptyList(), emptyList(), BattleSide.entries.associateWith {
            if (it == side) listOf(BattleTimedEffectView(effect, null, stacks = 1)) else emptyList()
        }))

    private fun slots(format: BattleFormat) = if (format == BattleFormat.DOUBLE) 2 else 1
}
