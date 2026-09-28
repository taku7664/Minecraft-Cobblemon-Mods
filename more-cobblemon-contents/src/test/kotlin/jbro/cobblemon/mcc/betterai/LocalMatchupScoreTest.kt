package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutProfile
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.AntiAceScore
import jbro.cobblemon.mcc.betterai.matchup.AntiAceToolKind
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalMatchupScoreTest {
    @Test
    fun `a knockout profile counts uses to the knockout, misses included`() {
        val sure = LocalKnockoutProfile.of(List(16) { 0.6 }, 1.0, 1.0, 6)
        assertEquals(listOf(1.0, 1.0, 0.0), sure.survival.take(3))
        assertEquals(2.0, sure.expectedUses, 1e-9)

        val coinFlip = LocalKnockoutProfile.of(List(16) { 0.6 }, 0.5, 1.0, 6)
        // Two landed uses knock out; within two uses that is one chance in four.
        assertEquals(0.75, coinFlip.survival[2], 1e-9)
        // Standing after one use: the miss (nothing taken) and the landed 0.6, equally likely.
        assertEquals(0.3, coinFlip.damageWhileStanding[1], 1e-9)

        val nothing = LocalKnockoutProfile.of(List(16) { 0.0 }, 1.0, 1.0, 6)
        assertTrue(nothing.expectedUses.isInfinite())
    }

    @Test
    fun `a roll one point short of the remaining HP is not a knockout`() {
        val profile = LocalKnockoutProfile.of(List(15) { 0.4999 } + 0.5, 1.0, 0.5, 6)
        assertEquals(1.0 / 16, 1.0 - profile.survival[1], 1e-9)
    }

    @Test
    fun `the faster one-hit knockout wins the exchange with its HP intact, and the mirror is its negation`() {
        val context = context(allySpeed = 150, foeSpeed = 100)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val matchup = scores.pokemon(ALLY, FOE)!!
        assertEquals(1.0, matchup.subjectMovesFirstProbability, 1e-9)
        assertEquals(1.0, matchup.winProbability, 1e-9)
        assertEquals(1.0, matchup.subjectRemainingHpOnWin, 1e-9)
        assertEquals(1.0, matchup.score, 1e-9)
        assertEquals(1.0, matchup.subjectMove!!.score, 1e-9)
        val mirror = scores.pokemon(FOE, ALLY)!!
        assertEquals(-1.0, mirror.score, 1e-9)
        assertEquals(0.0, mirror.winProbability, 1e-9)
    }

    @Test
    fun `trick room turns the same exchange around`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100))
        val reversed = scores.pokemon(ALLY, FOE, MatchupSpeedField.TRICK_ROOM_TOGGLED)!!
        assertEquals(0.0, reversed.subjectMovesFirstProbability, 1e-9)
        assertEquals(0.0, reversed.winProbability, 1e-9)
        assertEquals(-1.0, reversed.score, 1e-9)
    }

    @Test
    fun `a bench Pokemon is scored as it would enter, hazards included`() {
        val context = context(allySpeed = 150, foeSpeed = 100, rocksOnAllySide = true)
        val benched = LocalMatchupScoreCalculator.calculate(context).pokemon(BENCH, FOE)!!
        // Stealth Rock takes an eighth of a neutral Pokemon's HP on entry, and that is what it keeps on a win.
        assertEquals(0.875, benched.subjectRemainingHpOnWin, 1e-6)
    }

    @Test
    fun `a slower Pokemon that needs two hits against one that needs one loses outright`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 150, allyPower = 40.0))
        val matchup = scores.pokemon(ALLY, FOE)!!
        assertTrue(matchup.subjectMove!!.expectedHitsToKnockout > 1.0)
        assertEquals(0.0, matchup.winProbability, 1e-9)
        assertTrue(matchup.opponentRemainingHpOnLoss >= 0.99) { "the foe moved first and was never hit: $matchup" }
        assertTrue(matchup.score < -0.9)
    }

    @Test
    fun `dragon dance makes an ace of a Pokemon that loses the plain exchange`() {
        // Both sides need three hits; the foe is faster. One Dragon Dance costs a hit, then two hits win it first.
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0,
            foePower = 65.0, allySetup = mapOf("atk" to 1, "spe" to 1)))
        assertEquals(0.0, scores.pokemon(ALLY, FOE)!!.winProbability, 1e-9)
        val ace = scores.aces.getValue(ALLY)
        assertEquals(0.0, ace.naturalSweep, 1e-9)
        assertEquals("dragondance", ace.setupMoveId)
        assertEquals(1.0, ace.setupSafety, 1e-9)
        assertEquals(1.0, ace.boostedSweep, 1e-9)
        assertEquals(1.0, ace.score, 1e-9)
    }

    @Test
    fun `a setup turn that gets the user knocked out is worth nothing`() {
        // The foe two-hits and moves first: after one Swords Dance the user falls before it swings.
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0,
            foePower = 90.0, allySetup = mapOf("atk" to 2)))
        val ace = scores.aces.getValue(ALLY)
        assertEquals(0.0, ace.boostedSweep, 1e-9)
        assertEquals(0.0, ace.score, 1e-9)
    }

    @Test
    fun `the exchange takes the priority move that moves first over the stronger slow one`() {
        // The foe is faster and two-hits. The slow move two-hits too but never lands its second hit; the
        // weaker priority move needs a lucky two hits, and that is the only way to win.
        val quick = BattlePublicMoveOptionView("quick", BattleMoveCandidateView(typeId = "normal",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, accuracy = 100.0, priority = 1, currentPp = 8),
            BattlePublicMoveKnowledge.EXACT_OWN)
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 150, allyPower = 90.0,
            foePower = 90.0, allyExtra = listOf(quick)))
        val matchup = scores.pokemon(ALLY, FOE)!!
        assertEquals("probe", scores.moves(ALLY, FOE).first().moveId)
        assertEquals("quick", matchup.subjectMove!!.moveId)
        assertEquals(1.0, matchup.subjectMovesFirstProbability, 1e-9)
        assertTrue(matchup.winProbability > 0.0)
    }

    @Test
    fun `doubles bench Pokemon are scored too`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100, format = BattleFormat.DOUBLE))
        assertNotNull(scores.pokemon(BENCH, FOE))
        assertNotNull(scores.aces[BENCH])
    }

    @Test
    fun `haze stops a dragon dance ace that beats the plain attacker`() {
        val haze = BattlePublicMoveOptionView("haze", BattleMoveCandidateView(typeId = "ice",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.ALL_ACTIVE), BattlePublicMoveKnowledge.EXACT_OWN)
        // Unboosted, the faster ally wins a three-hit race; after one Dragon Dance the foe outspeeds and two-hits.
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 90, allyPower = 65.0,
            foePower = 65.0, foeSetup = mapOf("atk" to 1, "spe" to 1), allyExtra = listOf(haze)))
        assertEquals(1.0, scores.pokemon(ALLY, FOE)!!.winProbability, 1e-9)
        assertEquals("dragondance", scores.aces.getValue(FOE).setupMoveId)
        val anti = scores.antiAces.getValue(ALLY)
        assertEquals(FOE, anti.aceId)
        assertEquals(0.0, anti.tools.single { it.kind == AntiAceToolKind.OUTLASTS }.value, 1e-9)
        assertEquals(AntiAceToolKind.RESETS_BOOSTS, anti.bestTool!!.kind)
        assertEquals(1.0, anti.score, 1e-9)
    }

    @Test
    fun `a focus sash guarantees the first action and counts as a stopper`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 90, allyPower = 65.0,
            foePower = 65.0, foeSetup = mapOf("atk" to 1, "spe" to 1), allyItem = "focussash"))
        val anti = scores.antiAces.getValue(ALLY)
        assertEquals("focussash", anti.oneTimeSurvival)
        assertEquals(1.0, anti.actsBeforeKnockout, 1e-9)
        assertEquals(AntiAceScore.ONE_TIME_SURVIVAL_BONUS, anti.score, 1e-9)
    }

    private fun context(
        allySpeed: Int,
        foeSpeed: Int,
        allyPower: Double = 300.0,
        foePower: Double = 300.0,
        rocksOnAllySide: Boolean = false,
        allySetup: Map<String, Int>? = null,
        allyExtra: List<BattlePublicMoveOptionView> = emptyList(),
        foeSetup: Map<String, Int>? = null,
        foeExtra: List<BattlePublicMoveOptionView> = emptyList(),
        allyItem: String? = null,
        format: BattleFormat = BattleFormat.SINGLE,
    ): BattleDecisionContext {
        val field = if (!rocksOnAllySide) BattleFieldStateView.empty() else BattleFieldStateView(null, null, emptyList(), emptyList(),
            mapOf(BattleSide.ALLY to listOf(BattleTimedEffectView("stealthrock", null)), BattleSide.OPPONENT to emptyList()))
        val state = BattleStateView(UUID(0, 918), format, 1,
            listOf(pokemon(ALLY, BattleSide.ALLY, 0, allySpeed, allyItem), pokemon(BENCH, BattleSide.ALLY, null, allySpeed),
                pokemon(FOE, BattleSide.OPPONENT, 0, foeSpeed)), field,
            mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        return BattleDecisionContext(UUID(0, 919), state, listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(state.pokemon.map { pokemon ->
                val power = if (pokemon.side == BattleSide.ALLY) allyPower else foePower
                val knowledge = if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED
                val attack = BattlePublicMoveOptionView("probe", BattleMoveCandidateView(typeId = "normal",
                    damageCategory = BattleMoveDamageCategory.PHYSICAL, power = power, accuracy = 100.0, priority = 0, currentPp = 8), knowledge)
                val setup = (if (pokemon.side == BattleSide.ALLY) allySetup else foeSetup)?.let { stages ->
                    BattlePublicMoveOptionView(if (stages.size > 1) "dragondance" else "swordsdance", BattleMoveCandidateView(typeId = "normal",
                        damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
                        targetPattern = BattleMoveTargetPattern.SELF,
                        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                            BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = stages)), false)), knowledge)
                }
                val extra = if (pokemon.side == BattleSide.ALLY) allyExtra else foeExtra
                BattlePokemonActionCatalogView(pokemon.battlePokemonId, listOfNotNull(attack, setup) + extra, moveSetComplete = true)
            }))
    }

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, speed: Int, item: String? = null) =
        BattlePokemonStateView(id, side, slot, "fixture:matchup", null, 50, 1.0, null, emptyMap(), emptySet(), null, item, false,
            knownTypeIds = setOf("normal"),
            combatStats = BattleCombatStatRangesView(maxHp = BattleIntegerRange(150, 150),
                attack = BattleIntegerRange(150, 150), defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(100, 100), specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(speed, speed), knowledge = if (side == BattleSide.ALLY)
                    BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private companion object {
        val ALLY = UUID(0, 1)
        val BENCH = UUID(0, 2)
        val FOE = UUID(0, 3)
    }
}
