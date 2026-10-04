package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicMoveOutcomeBranchProjector
import jbro.cobblemon.mcc.betterai.calculation.LocalChanceModel
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalCriticalMechanicalInputsTest {
    @Test
    fun `a critical branch ignores the attackers negative stages and the targets positive stages`() {
        val neutral = fixture(critical = true)
        val weakened = fixture(critical = true, attackerStages = mapOf("attack" to -4), targetStages = mapOf("defense" to 4))
        assertEquals(rolls(neutral), rolls(weakened))
        assertTrue(rolls(neutral).average() > rolls(fixture(critical = false)).average())
    }

    @Test
    fun `a critical branch bypasses Reflect but keeps favourable attack stages`() {
        val neutral = rolls(fixture(critical = true))
        assertEquals(neutral, rolls(fixture(critical = true, reflect = true)))
        assertTrue(rolls(fixture(critical = true, attackerStages = mapOf("attack" to 2))).average() > neutral.average())
        assertTrue(rolls(fixture(critical = true, targetStages = mapOf("defense" to -2))).average() > neutral.average())
    }

    @Test
    fun `a guaranteed critical move respects public Battle Armor and Ability Shield`() {
        val ordinary = rolls(fixture(critical = false))
        assertEquals(ordinary, rolls(fixture(alwaysCritical = true, targetAbility = "battlearmor")))
        assertTrue(rolls(fixture(alwaysCritical = true, targetAbility = "battlearmor", attackerAbility = "moldbreaker")).average() > ordinary.average())
        assertEquals(ordinary, rolls(fixture(alwaysCritical = true, targetAbility = "battlearmor", attackerAbility = "moldbreaker", targetItem = "abilityshield")))
    }

    @Test
    fun `Laser Focus uses real critical damage through defensive stages instead of multiplying the ordinary roll`() {
        val context = fixture(targetHp = 0.25, attackerStages = mapOf("attack" to -6), targetStages = mapOf("defense" to 6))
        val actor = context.state.pokemon.single { it.side == BattleSide.ALLY }
        val focused = context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.battlePokemonId == actor.battlePokemonId) it.copyState(knownVolatileEffectIds = setOf("laserfocus")) else it
        }))
        assertTrue(rolls(focused).all { it >= 0.25 })
        val branches = PublicMoveOutcomeBranchProjector.withChanceModel(LocalChanceModel.HIGH_ROLL) {
            PublicMoveOutcomeBranchProjector.project(focused.candidates.single(), focused, BattleSide.ALLY)
        }
        assertEquals(1.0, branches.sumOf { it.probability }, 1e-9)
        assertTrue(branches.all { it.damageFraction >= 0.25 })
    }

    private fun rolls(context: BattleDecisionContext) = requireNotNull(
        PublicBattleTacticalCalculator.conservativeDamageRollFractions(context.candidates.single(), context, BattleSide.ALLY))

    @Test
    fun `an implicit single target retains its public critical immunity`() {
        val context = fixture(targetAbility = "shellarmor")
        val action = context.candidates.single()
        val implicit = BattleActionCandidate(action.actionId, action.kind, actorSlot = action.actorSlot,
            moveSlot = action.moveSlot, moveId = action.moveId, moveDetails = action.moveDetails)
        val position = context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(knownVolatileEffectIds = setOf("laserfocus")) else it
        }), candidates = listOf(implicit))
        val branches = PublicMoveOutcomeBranchProjector.project(implicit, position, BattleSide.ALLY)
        assertTrue(branches.none { it.critical })
    }

    private fun fixture(
        critical: Boolean = false, alwaysCritical: Boolean = false, reflect: Boolean = false,
        attackerStages: Map<String, Int> = emptyMap(), targetStages: Map<String, Int> = emptyMap(),
        attackerAbility: String? = null, targetAbility: String? = null, targetItem: String? = null, targetHp: Double = 1.0,
    ): BattleDecisionContext {
        val actor = mon(BattleSide.ALLY, attackerStages, attackerAbility)
        val target = mon(BattleSide.OPPONENT, targetStages, targetAbility, targetItem, targetHp)
        val field = if (reflect) BattleFieldStateView(null, null, emptyList(), emptyList(), mapOf(
            BattleSide.ALLY to emptyList(), BattleSide.OPPONENT to listOf(BattleTimedEffectView("reflect", 5)))) else BattleFieldStateView.empty()
        val state = BattleStateView(UUID(0, 800), BattleFormat.SINGLE, 3, listOf(actor, target), field,
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        val effects = if (alwaysCritical) BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            listOf(BattleMoveEffectView(BattleMoveEffectKind.ALWAYS_CRITICAL, BattleMoveEffectTarget.SELECTED_TARGET)), false) else null
        val action = BattleActionCandidate("probe", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "tackle",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 80.0, 100.0, 0, 10,
                effects = effects), tags = if (critical) setOf("criticalhit") else emptySet())
        return BattleDecisionContext(UUID(0, 801), state, listOf(action), Long.MAX_VALUE)
    }

    private fun mon(side: BattleSide, stages: Map<String, Int>, ability: String?, item: String? = null, hp: Double = 1.0) =
        BattlePokemonStateView(UUID(0, side.ordinal.toLong() + 1), side, 0, "fixture:critical", null, 50, hp, null,
            stages, emptySet(), ability, item, false, knownTypeIds = setOf("normal"),
            combatStats = BattleCombatStatRangesView(BattleIntegerRange(200, 200), BattleIntegerRange(120, 120),
                BattleIntegerRange(100, 100), BattleIntegerRange(120, 120), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN
                    else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
}
