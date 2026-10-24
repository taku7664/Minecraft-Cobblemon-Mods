package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.*
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalRepresentativeHitOutcomesTest {
    @Test fun `representative damage retains probability mass and both KO classes`() {
        val action = move(accuracy = 70.0)
        val plain = context(action, mon(FOE, BattleSide.OPPONENT))
        val raw = PublicBattleTacticalCalculator.conservativeDamageRollFractions(action, plain, BattleSide.ALLY)!!
        val threshold = raw.average() * (1 + 0.5 / 24)
        val c = context(action, mon(FOE, BattleSide.OPPONENT, hp = threshold))
        val paths = PublicMoveOutcomeBranchProjector.representative(action, c, BattleSide.ALLY, 1)
        assertEquals(1.0, paths.sumOf { it.probability }, 1e-12)
        assertEquals(0.7, paths.filter { it.hit }.sumOf { it.probability }, 1e-12)
        assertEquals(3, paths.size)
        assertTrue(paths.any { it.hit && it.damageFraction >= threshold })
        assertTrue(paths.any { it.hit && it.damageFraction < threshold })
        assertEquals(PublicBattleTacticalCalculator.conservativeDamageRollFractions(action,c,BattleSide.ALLY)!!.average() * (1 + 0.5 / 24), paths.sumOf { it.probability * it.damageFraction } / 0.7, 1e-12)
    }

    @Test fun `five hit contact chances accumulate once without Cartesian hit expansion`() {
        val action = move(count = 5)
        val c = context(action, mon(FOE, BattleSide.OPPONENT, ability = "static"), mon(OWN, BattleSide.ALLY, ability = "poisontouch"))
        val paths = LocalMoveHitSequence.project(c.state, OWN, FOE, action, 0.1, emptyList(), false, c, BattleSide.ALLY,
            hitCountOverride = 5, representative = true)
        assertEquals(4, paths.size)
        assertEquals(1.0, paths.sumOf { it.probability }, 1e-12)
        val chance = 1.0 - Math.pow(0.7, 5.0)
        assertEquals(chance, paths.filter { it.state.pokemon.first().statusId == "par" }.sumOf { it.probability }, 1e-12)
        assertEquals(chance, paths.filter { it.state.pokemon.last().statusId == "psn" }.sumOf { it.probability }, 1e-12)
        assertTrue(paths.all { kotlin.math.abs(it.state.pokemon.last().hpFraction - 0.9) < 1e-12 })
    }

    @Test fun `helmet and stamina still trigger on each of five representative hits`() {
        val action = move(count = 5)
        val c = context(action, mon(FOE, BattleSide.OPPONENT, ability = "stamina", item = "rockyhelmet"))
        val paths = LocalMoveHitSequence.project(c.state, OWN, FOE, action, 0.1, emptyList(), false, c, BattleSide.ALLY,
            hitCountOverride = 5, representative = true)
        assertEquals(1, paths.size)
        assertEquals(1.0 / 6, paths.single().state.pokemon.first().hpFraction, 1e-12)
        assertEquals(5, paths.single().state.pokemon.last().statStages["defence"])
    }

    @Test fun `joint representative hits consume a healing berry once`() {
        val a = move(); val b = BattleActionCandidate("partner", BattleActionKind.USE_MOVE, 1, 0, "tackle",
            targets = a.targets, moveDetails = a.moveDetails)
        val partner = mon(UUID(0, 3), BattleSide.ALLY).copyState(activeSlot = 1)
        val original = context(a, mon(FOE, BattleSide.OPPONENT, hp = 81.0 / 146, item = "sitrusberry"))
        val c = original.copy(state = original.state.copyState(pokemon = original.state.pokemon + partner))
        val joint = BattleActionCandidate("joint", BattleActionKind.COMPOSITE, componentActionIds = listOf(a.actionId,b.actionId), componentActions = listOf(a,b))
        val tuning = LocalDecisionTuning.CURRENT.copy(representativeHitOutcomes = true)
        LocalActiveTuning.with(tuning) {
            val independent = listOf(a,b).sumOf { LocalRootHitReactionEvaluator.score(it,c,tuning)?.total ?: 0.0 }
            val correction = LocalRootHitReactionEvaluator.compositeCorrection(joint,c,tuning)
            assertEquals(-36.0 / 146 * 100, independent + correction.total, 1e-9)
        }
    }

    @Test fun `representative projector reduces raw roll states and keeps nonnegative normalized weights`() {
        val action = move()
        val c = context(action, mon(FOE, BattleSide.OPPONENT, item = "sitrusberry"))
        val exact = LocalMoveImpactProjector.project(action,c,BattleSide.ALLY,LocalProjectedActionCalculationCache())
        val fast = LocalActiveTuning.with(LocalDecisionTuning.CURRENT.copy(representativeHitOutcomes = true)) {
            LocalMoveImpactProjector.project(action,c,BattleSide.ALLY,LocalProjectedActionCalculationCache())
        }
        assertTrue(fast.size < exact.size)
        assertTrue(fast.size <= 2)
        assertTrue(fast.all { it.probability >= 0 && it.probability.isFinite() })
        assertEquals(1.0, fast.sumOf { it.probability }, 1e-12)
    }

    @Test fun `unchanged representative exchange cannot reverse the existing matchup`() {
        val base = PokemonMatchupScore(OWN,FOE,MatchupSpeedField.CURRENT,null,null,1.0,1.0,1.0,1.0,1.0)
        val approximate = base.copy(winProbability = 0.0,score = -1.0)
        val result = LocalSimpleMatchup.transition(base,approximate,approximate)
        assertEquals(base.winProbability,result.winProbability,1e-12)
        assertEquals(base.score,result.score,1e-12)
        val worn = approximate.copy(subjectRemainingHpOnWin = 0.8)
        val after = LocalSimpleMatchup.transition(base,approximate,worn)
        assertEquals(0.9,after.score,1e-12)
        assertEquals(0.8,after.subjectRemainingHpOnWin,1e-12)
    }

    @Test fun `one hundred percent random critical cannot create a zero weight NaN branch`() {
        val a = move()
        val own = mon(OWN,BattleSide.ALLY,ability = "superluck",item = "scopelens")
            .copyState(knownVolatileEffectIds = setOf("focusenergy"))
        val c = context(a,mon(FOE,BattleSide.OPPONENT,ability = "angerpoint"),own)
        val paths = PublicMoveOutcomeBranchProjector.representative(a,c,BattleSide.ALLY,1)
        assertEquals(1.0,paths.sumOf { it.probability },1e-12)
        assertTrue(paths.all { it.probability > 0.0 && it.damageFraction.isFinite() && it.critical })
    }

    private fun move(accuracy: Double = 100.0, count: Int = 1) = BattleActionCandidate("tackle", BattleActionKind.USE_MOVE, 0, 0, "tackle",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT,0)), moveDetails = BattleMoveCandidateView("normal",
            BattleMoveDamageCategory.PHYSICAL,40.0,accuracy,0,20,effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                if (count > 1) listOf(BattleMoveEffectView(BattleMoveEffectKind.MULTI_HIT,BattleMoveEffectTarget.SELECTED_TARGET,
                    amountRange = BattleIntegerRange(count,count))) else emptyList(),false,mechanicFlags = setOf("contact"))))
    private fun mon(id: UUID, side: BattleSide, hp: Double = 1.0, ability: String? = null, item: String? = null) =
        BattlePokemonStateView(id,side,0,"mew",null,50,hp,null,emptyMap(),emptySet(),ability,item,false,knownTypeIds = setOf("psychic"),
            combatStats = BattleCombatStatRangesView(BattleIntegerRange(146,146),BattleIntegerRange(100,100),BattleIntegerRange(100,100),
                BattleIntegerRange(100,100),BattleIntegerRange(100,100),BattleIntegerRange(100,100),if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
    private fun context(a: BattleActionCandidate, foe: BattlePokemonStateView, own: BattlePokemonStateView = mon(OWN,BattleSide.ALLY)) =
        BattleDecisionContext(UUID(0,9),BattleStateView(UUID(0,8),BattleFormat.DOUBLE,1,listOf(own,foe),BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 },emptyList(),emptyList()),listOf(a),Long.MAX_VALUE)
    private companion object { val OWN = UUID(0,1); val FOE = UUID(0,2) }
}
