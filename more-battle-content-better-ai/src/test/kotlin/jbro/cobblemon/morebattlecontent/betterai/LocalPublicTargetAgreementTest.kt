package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalPublicTargetAgreementTest {
    @Test
    fun `spread primary target receives weather and its own defensive modifier`() {
        for ((ability, multiplier) in listOf(null to 1.5, "thickfat" to 0.75)) {
            val source = context("fire", BattleMoveTargetPattern.ALL_OPPONENTS, weather = "sunnyday", primaryAbility = ability)
            val calculated = PublicBattleTacticalCalculator.calculate(source)
            val projection = LocalPublicMechanicsKernel.projectMove(calculated.candidates.single(), calculated)
            assertEquals(0.25, projection.targetHpFraction)
            assertEquals(multiplier, projection.knownDamageMultiplier, 1e-9)
            assertFalse(projection.publiclyNullified)
        }
    }

    @Test
    fun `redirected move modifier refers to the same defender as its damage facts`() {
        val source = context("electric", BattleMoveTargetPattern.SELECTED_OPPONENT, otherAbility = "lightningrod")
        val calculated = PublicBattleTacticalCalculator.calculate(source)
        val candidate = calculated.candidates.single()
        val projection = LocalPublicMechanicsKernel.projectMove(candidate, calculated)
        assertEquals(0.0, candidate.facts!!.typeChartMultiplier)
        assertTrue(projection.publiclyNullified)
        assertEquals(PublicBattleTacticalCalculator.primaryTargetHpFraction(candidate, calculated, BattleSide.ALLY),
            projection.targetHpFraction)
        assertEquals(0.75, projection.targetHpFraction)
    }

    @Test
    fun `ambiguous unselected non spread target stays unknown`() {
        val source = context("fire", BattleMoveTargetPattern.SELECTED_OPPONENT, weather = "sunnyday", explicit = false)
        val projection = LocalPublicMechanicsKernel.projectMove(source.candidates.single(), source)
        assertNull(projection.targetHpFraction)
        assertEquals(1.0, projection.knownDamageMultiplier)
    }

    private fun context(type: String, pattern: BattleMoveTargetPattern, weather: String? = null,
        primaryAbility: String? = null, otherAbility: String? = null,
        explicit: Boolean = pattern == BattleMoveTargetPattern.SELECTED_OPPONENT): BattleDecisionContext {
        val state = BattleStateView(UUID(0, 850), BattleFormat.DOUBLE, 1,
            listOf(mon(1, BattleSide.ALLY, 0, 1.0, null), mon(2, BattleSide.ALLY, 1, 1.0, null),
                mon(4, BattleSide.OPPONENT, 1, 0.75, otherAbility), mon(3, BattleSide.OPPONENT, 0, 0.25, primaryAbility)),
            BattleFieldStateView(weather?.let { BattleTimedEffectView(it, 3) }, null, emptyList(), emptyList(),
                BattleSide.entries.associateWith { emptyList() }),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
        val action = BattleActionCandidate("probe", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "probe", targets = if (explicit) listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)) else emptyList(),
            moveDetails = BattleMoveCandidateView(typeId = type, damageCategory = BattleMoveDamageCategory.SPECIAL,
                power = 90.0, accuracy = 100.0, priority = 0, currentPp = 8, targetPattern = pattern))
        return BattleDecisionContext(UUID(0, 851), state, listOf(action), Long.MAX_VALUE)
    }

    private fun mon(id: Long, side: BattleSide, slot: Int, hp: Double, ability: String?) = BattlePokemonStateView(
        UUID(0, id), side, slot, "test:target", null, 50, hp, null, emptyMap(), emptySet(), ability, null, false,
        knownTypeIds = setOf("normal"), combatStats = BattleCombatStatRangesView(
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
}
