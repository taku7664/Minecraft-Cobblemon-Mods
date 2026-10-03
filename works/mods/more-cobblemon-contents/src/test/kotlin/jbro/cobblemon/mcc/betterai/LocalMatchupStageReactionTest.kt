package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.StopToolKind
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The sweep, stopper and spent-turn tables must read the actual public boost, not its raw declaration. */
class LocalMatchupStageReactionTest {
    @Test
    fun `Contrary Dragon Dance cannot turn a losing exchange into a sweep`() {
        val score = LocalMatchupScoreCalculator.calculate(context(ownAbility = "contrary", setup = true)).sweeps.getValue(OWN)
        assertEquals(0.0, score.naturalSweep, 1e-9)
        assertEquals(0.0, score.boostedSweep, 1e-9)
        assertNull(score.setupMoveId)
    }

    @Test
    fun `the active setup window also reads Contrary`() {
        val score = LocalMatchupScoreCalculator.calculate(context(ownAbility = "contrary", setup = true)).sweeps.getValue(OWN)
        assertEquals(0.0, score.windowSweep!!, 1e-9)
    }

    @Test
    fun `Simple one-use setup is equivalent to the declared doubled stages`() {
        val simple = LocalMatchupScoreCalculator.calculate(context(ownAbility = "simple", setup = true, foeSpeed = 170))
            .sweeps.getValue(OWN)
        val doubled = LocalMatchupScoreCalculator.calculate(context(setup = true, setupAmount = 2, foeSpeed = 170))
            .sweeps.getValue(OWN)
        assertTrue(doubled.boostedSweep > 0.0)
        assertEquals(doubled.boostedSweep, simple.boostedSweep, 1e-9)
        assertEquals(doubled.setupUses, simple.setupUses)
        assertEquals(doubled.windowSweep!!, simple.windowSweep!!, 1e-9)
    }

    @Test
    fun `White Herb restores the first Contrary setup but never creates a positive boost`() {
        val score = LocalMatchupScoreCalculator.calculate(context(ownAbility = "contrary", ownItem = "whiteherb", setup = true))
            .sweeps.getValue(OWN)
        assertEquals(0.0, score.boostedSweep, 1e-9)
        assertEquals(0.0, score.windowSweep!!, 1e-9)
    }

    @Test
    fun `spent-turn self setup prices the reversed Contrary change`() {
        val context = context(ownAbility = "contrary", setup = true)
        val move = LocalMatchupScoreCalculator.calculate(context).statusMoves(OWN, FOE).single { it.moveId == "dragondance" }
        assertTrue(move.afterLanding <= move.afterMiss) { move.toString() }
    }

    @Test
    fun `a Clear Body target receives no spent-turn stat drop gain`() {
        assertBlockedDrop("clearbody", "")
    }

    @Test
    fun `a White Herb target receives no spent-turn stat drop gain`() {
        assertBlockedDrop(null, "whiteherb")
    }

    @Test
    fun `a Contrary target cannot be credited as weakened by Charm`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(foeAbility = "contrary", drop = true))
        val move = scores.statusMoves(OWN, FOE).single { it.moveId == "charm" }
        assertTrue(move.afterLanding <= move.afterMiss) { move.toString() }
        val stop = scores.stops.getValue(OWN)
        assertTrue(stop.tools.single { it.kind == StopToolKind.STAT_DROP }.value <=
            stop.tools.single { it.kind == StopToolKind.OUTLASTS }.value)
    }

    @Test
    fun `a normal target remains vulnerable to the same drop`() {
        val move = LocalMatchupScoreCalculator.calculate(context(drop = true)).statusMoves(OWN, FOE).single { it.moveId == "charm" }
        assertTrue(move.afterLanding > move.afterMiss) { move.toString() }
    }

    private fun assertBlockedDrop(ability: String?, item: String) {
        val scores = LocalMatchupScoreCalculator.calculate(context(foeAbility = ability, foeItem = item, drop = true))
        val move = scores.statusMoves(OWN, FOE).single { it.moveId == "charm" }
        assertEquals(move.afterMiss, move.afterLanding, 1e-9)
        val stop = scores.stops.getValue(OWN)
        assertEquals(stop.tools.single { it.kind == StopToolKind.OUTLASTS }.value,
            stop.tools.single { it.kind == StopToolKind.STAT_DROP }.value, 1e-9)
    }

    private fun context(ownAbility: String? = null, foeAbility: String? = null,
        ownItem: String = "", foeItem: String = "", setup: Boolean = false, setupAmount: Int = 1,
        drop: Boolean = false, foeSpeed: Int = 120): BattleDecisionContext {
        fun pokemon(id: UUID, side: BattleSide, speed: Int, ability: String?, item: String) = BattlePokemonStateView(
            id, side, 0, "fixture:stage-reaction", null, 50, 1.0, null, emptyMap(), emptySet(), ability, item, false,
            knownTypeIds = setOf("normal"), combatStats = BattleCombatStatRangesView(
                BattleIntegerRange(150, 150), BattleIntegerRange(150, 150), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(speed, speed),
                if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
        val own = pokemon(OWN, BattleSide.ALLY, 100, ownAbility, ownItem)
        val foe = pokemon(FOE, BattleSide.OPPONENT, foeSpeed, foeAbility, foeItem)
        val state = BattleStateView(UUID(0, 703), BattleFormat.SINGLE, 1, listOf(own, foe), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        fun attack(knowledge: BattlePublicMoveKnowledge) = BattlePublicMoveOptionView("probe", BattleMoveCandidateView(
            "normal", BattleMoveDamageCategory.PHYSICAL, 65.0, 100.0, 0, 8), knowledge)
        fun effectMove(id: String, target: BattleMoveEffectTarget, stages: Map<String, Int>, pattern: BattleMoveTargetPattern) =
            BattlePublicMoveOptionView(id, BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 8,
                pattern, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, target, statStages = stages)), false)),
                BattlePublicMoveKnowledge.EXACT_OWN)
        val ownMoves = listOf(attack(BattlePublicMoveKnowledge.EXACT_OWN)) + listOfNotNull(
            if (setup) effectMove("dragondance", BattleMoveEffectTarget.USER,
                mapOf("atk" to setupAmount, "spe" to setupAmount), BattleMoveTargetPattern.SELF) else null,
            if (drop) effectMove("charm", BattleMoveEffectTarget.SELECTED_TARGET,
                mapOf("atk" to -2), BattleMoveTargetPattern.SELECTED_OPPONENT) else null)
        return BattleDecisionContext(UUID(0, 704), state, listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)),
            Long.MAX_VALUE, publicActionCatalog = BattlePublicActionCatalogView(listOf(
                BattlePokemonActionCatalogView(OWN, ownMoves, moveSetComplete = true),
                BattlePokemonActionCatalogView(FOE, listOf(attack(BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true))))
    }

    private companion object {
        val OWN = UUID(0, 701)
        val FOE = UUID(0, 702)
    }
}
