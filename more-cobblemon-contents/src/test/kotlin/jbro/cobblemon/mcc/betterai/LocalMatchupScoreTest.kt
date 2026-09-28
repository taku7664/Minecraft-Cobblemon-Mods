package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutProfile
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor
import jbro.cobblemon.mcc.betterai.matchup.LocalPublicFailureTriage
import jbro.cobblemon.mcc.betterai.matchup.AntiAceScore
import jbro.cobblemon.mcc.betterai.matchup.AntiAceToolKind
import jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate
import jbro.cobblemon.mcc.betterai.matchup.LocalStatusMoveTriage
import jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRules
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.matchup.StatusMoveMatchupScore
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

    @Test
    fun `the setup gate lets the dragon dance through when it wins and nothing stops it`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertTrue(verdict.passes) { verdict.failures.toString() }
    }

    @Test
    fun `the setup gate refuses a setup that is not an ace`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 90.0, allySetup = mapOf("atk" to 2))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertFalse(verdict.passes)
        assertTrue("ace" in verdict.failures) { verdict.failures.toString() }
    }

    @Test
    fun `haze trades a turn for the boost and does not stop the setup`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1), foeExtra = listOf(statusMove("haze", BattleMoveTargetPattern.ALL_ACTIVE)))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertTrue(verdict.failures.none { it.startsWith("stopper:") }) { verdict.failures.toString() }
    }

    @Test
    fun `encore on the field always stops the setup`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1), foeExtra = listOf(statusMove("encore")))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertFalse(verdict.passes)
        assertTrue(verdict.failures.any { it.startsWith("stopper:") && it.endsWith(":encore") }) { verdict.failures.toString() }
    }

    @Test
    fun `an ace facing destiny bond does not set up and retreats`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, benchPower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1), foeExtra = listOf(statusMove("destinybond", BattleMoveTargetPattern.SELF)))
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, scores)!!
        assertTrue(verdict.failures.any { it.endsWith(":destiny_bond") }) { verdict.failures.toString() }
        val ally = context.state.pokemon.first { it.battlePokemonId == ALLY }
        assertTrue(LocalSetupGate.retreatReasons(ally, context, scores).isNotEmpty())
        val withoutBond = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, benchPower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1))
        fun switchCredit(position: BattleDecisionContext) = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), position,
            LocalMatchupScoreCalculator.calculate(position)).adjustments["switch"] ?: 0.0
        assertTrue(switchCredit(context) > switchCredit(withoutBond)) { "${switchCredit(context)} vs ${switchCredit(withoutBond)}" }
    }

    private fun statusMove(id: String, pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT) =
        BattlePublicMoveOptionView(id, BattleMoveCandidateView(typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8, targetPattern = pattern), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)

    @Test
    fun `a haze user on the bench does not stop the setup until it is in front of it`() {
        val haze = BattlePublicMoveOptionView("haze", BattleMoveCandidateView(typeId = "ice",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.ALL_ACTIVE), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1), secondFoe = true, benchFoeExtra = listOf(haze))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertTrue(verdict.failures.none { it.startsWith("stopper:") }) { verdict.failures.toString() }
    }

    @Test
    fun `an attack that raises its user's stats is not a setup move for the gate`() {
        val context = context(allySpeed = 100, foeSpeed = 120)
        val flameCharge = BattleActionCandidate("flamecharge", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = "flamecharge",
            moveDetails = BattleMoveCandidateView(typeId = "fire", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 50.0,
                accuracy = 100.0, priority = 0, currentPp = 8, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = mapOf("spe" to 1))), false)))
        assertFalse(LocalSetupGate.raisesOwnStats(flameCharge))
        assertNull(LocalSetupGate.evaluate(flameCharge, context, LocalMatchupScoreCalculator.calculate(context)))
    }

    @Test
    fun `will-o-wisp is worth its turn against a physical attacker and not against a special one`() {
        val wisp = BattlePublicMoveOptionView("willowisp", BattleMoveCandidateView(typeId = "fire",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, valueId = "brn")), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        // The faster foe three-hits the ally, which three-hits it back and loses the race. Burned, the physical
        // foe needs more hits than the ally has left even after the turn spent on the burn.
        fun burn(category: BattleMoveDamageCategory) = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120,
            allyPower = 65.0, foePower = 65.0, foeCategory = category, allyExtra = listOf(wisp))).statusMoves(ALLY, FOE).single()
        val physical = burn(BattleMoveDamageCategory.PHYSICAL)
        val special = burn(BattleMoveDamageCategory.SPECIAL)
        assertEquals("willowisp", physical.moveId)
        assertTrue(physical.score > 0.0) { physical.toString() }
        assertTrue(special.score < 0.0) { special.toString() }
    }

    @Test
    fun `a status move that only spends the turn is ruled out, in singles only`() {
        val wisp = BattlePublicMoveOptionView("willowisp", BattleMoveCandidateView(typeId = "fire",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, valueId = "brn")), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        val action = BattleActionCandidate("wisp", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "willowisp",
            moveDetails = wisp.details)
        fun wasted(category: BattleMoveDamageCategory, format: BattleFormat = BattleFormat.SINGLE): Boolean {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, foeCategory = category,
                allyExtra = listOf(wisp), format = format)
            return LocalStatusMoveTriage.wasted(action, context, LocalMatchupScoreCalculator.calculate(context))
        }
        assertTrue(wasted(BattleMoveDamageCategory.SPECIAL))
        assertFalse(wasted(BattleMoveDamageCategory.PHYSICAL))
        // A doubles move without a declared target is left to the search.
        assertFalse(wasted(BattleMoveDamageCategory.SPECIAL, BattleFormat.DOUBLE))
    }

    @Test
    fun `a doubles burn counts what it does for the partner and a special target still wastes it`() {
        val action = BattleActionCandidate("wisp", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "willowisp",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)), moveDetails = wisp().details)
        val partnerAttack = BattleActionCandidate("partner", BattleActionKind.USE_MOVE, actorSlot = 1, moveSlot = 0, moveId = "probe")
        val turn = BattleActionCandidate("turn", BattleActionKind.COMPOSITE, componentActionIds = listOf("wisp", "partner"),
            componentActions = listOf(action, partnerAttack))
        fun judged(category: BattleMoveDamageCategory): Pair<StatusMoveMatchupScore, Boolean> {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, foeCategory = category,
                allyExtra = listOf(wisp()), format = BattleFormat.DOUBLE, benchActive = true)
            val scores = LocalMatchupScoreCalculator.calculate(context)
            return scores.statusMoves(ALLY, FOE).single { it.moveId == "willowisp" } to LocalStatusMoveTriage.wasted(turn, context, scores)
        }
        val (physical, physicalWasted) = judged(BattleMoveDamageCategory.PHYSICAL)
        assertTrue(physical.partnerGain > 0.0) { physical.toString() }
        assertFalse(physicalWasted)
        val (special, specialWasted) = judged(BattleMoveDamageCategory.SPECIAL)
        assertEquals(0.0, special.partnerGain, 1e-9)
        assertTrue(specialWasted) { special.toString() }
    }

    @Test
    fun `a burn on a target that already has a status publicly fails and is ruled out`() {
        val action = BattleActionCandidate("wisp", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "willowisp",
            moveDetails = wisp().details)
        val attack = attackAction()
        fun fails(status: String?) = context(allySpeed = 100, foeSpeed = 120, allyExtra = listOf(wisp()), foeStatus = status)
            .let { LocalPublicFailureTriage.fails(action, it) to LocalPublicFailureTriage.fails(attack, it) }
        assertEquals(true to false, fails("par"))
        assertEquals(false to false, fails(null))
    }

    @Test
    fun `a doubles spread move is scored against both opponents it hits`() {
        val slide = BattlePublicMoveOptionView("rockslide", BattleMoveCandidateView(typeId = "rock",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 75.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS), BattlePublicMoveKnowledge.EXACT_OWN)
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyExtra = listOf(slide),
            format = BattleFormat.DOUBLE, secondFoe = true, secondFoeActive = true))
        val primary = scores.moves(ALLY, FOE).single { it.moveId == "rockslide" }
        val secondary = scores.moves(ALLY, FOE2).single { it.moveId == "rockslide" }
        assertEquals(primary.maximumDamageFraction, secondary.maximumDamageFraction, 0.01)
    }

    @Test
    fun `the predicted opponent attacks when it wins and protects when it is about to fall`() {
        val protect = BattlePublicMoveOptionView("protect", BattleMoveCandidateView(typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 4, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.SELF, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER)), false)),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        fun top(allyPower: Double, foePower: Double, events: List<BattleObservedEventView> = emptyList()): String? {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = allyPower, foePower = foePower,
                foeExtra = listOf(protect), events = events)
            return LocalOpponentIntentPredictor.predict(context, LocalMatchupScoreCalculator.calculate(context))
                .single().options.first().moveId
        }
        assertEquals("probe", top(allyPower = 40.0, foePower = 300.0))
        assertEquals("protect", top(allyPower = 300.0, foePower = 40.0))
        // A second Protect in a row mostly fails.
        val protectedBefore = listOf(BattleObservedEventView(1, 0, BattleObservedEventKind.MOVE_USED, FOE, emptyList(), "protect"))
        assertEquals("probe", top(allyPower = 300.0, foePower = 40.0, events = protectedBefore))
    }

    private fun wisp() = BattlePublicMoveOptionView("willowisp", BattleMoveCandidateView(typeId = "fire",
        damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
        targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
            BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, valueId = "brn")), false)),
        BattlePublicMoveKnowledge.EXACT_OWN)

    @Test
    fun `parting shot declares the stat drop its callback applies`() {
        val parsed = BattleDeclarativeMoveEffects.parse(
            "const Moves = { partingshot: { num: 575, category: \"Status\", target: \"normal\", selfSwitch: true, " +
                "onHit(target, source, move) { const success = this.boost({ atk: -1, spa: -1 }, target, source); } } };",
        ).getValue("partingshot").effects
        assertTrue(parsed.any { it.kind == BattleMoveEffectKind.SWITCH_USER })
        val drop = parsed.single { it.kind == BattleMoveEffectKind.STAT_STAGE }
        assertEquals(BattleMoveEffectTarget.SELECTED_TARGET, drop.target)
        assertEquals(mapOf("atk" to -1, "spa" to -1), drop.statStages)
    }

    @Test
    fun `status moves are scored in doubles too`() {
        val snarl = BattlePublicMoveOptionView("partingshot", BattleMoveCandidateView(typeId = "dark",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.SELECTED_TARGET, statStages = mapOf("atk" to -1, "spa" to -1))), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0,
            foePower = 65.0, allyExtra = listOf(snarl), format = BattleFormat.DOUBLE))
        assertEquals(listOf("partingshot"), scores.statusMoves(ALLY, FOE).map { it.moveId })
    }

    @Test
    fun `a switch-in takes the hazards and the hit meant for the Pokemon it replaces`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100, foePower = 65.0,
            rocksOnAllySide = true))
        val switchIn = scores.switchIn(BENCH, FOE, ALLY)!!
        assertEquals("probe", switchIn.predictedMoveId)
        assertEquals(1.0, switchIn.predictedSurvival, 1e-9)
        assertTrue(switchIn.hpAfterEntry < 0.875 - 0.3) { switchIn.toString() }
        assertNotNull(switchIn.afterEntry)
    }

    @Test
    fun `the only answer to an opponent is the one worth preserving`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100, benchPower = 40.0))
        val sole = scores.preserves.getValue(ALLY)
        assertEquals(listOf(FOE), sole.soleAnswerTo)
        assertEquals(1.0, sole.score, 1e-9)
        assertEquals(0.0, scores.preserves.getValue(BENCH).score, 1e-9)
    }

    @Test
    fun `a switch-in that the predicted hit knocks out is ruled out`() {
        // The faster ally knocks the foe out first; the foe knocks out anything that walks in.
        val context = context(allySpeed = 150, foeSpeed = 100)
        val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, LocalMatchupScoreCalculator.calculate(context))
        assertEquals(LocalSwitchRules.SWITCH_IN_DIES, judgement.exclusions["switch"])
    }

    @Test
    fun `a switch that wins what staying loses is credited by the difference`() {
        // Staying, the weak ally loses a race it moves first in; the bench's one-hit knockout wins it after the hit.
        val context = context(allySpeed = 150, foeSpeed = 100, allyPower = 40.0, benchPower = 300.0, foePower = 65.0)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, scores)
        val gain = scores.switchIn(BENCH, FOE, ALLY)!!.score - scores.pokemon(ALLY, FOE)!!.score
        assertTrue(gain >= LocalSwitchRules.SWITCH_MARGIN) { "gain $gain" }
        assertEquals(gain * LocalSwitchRules.SCORE_SCALE, judgement.adjustments.getValue("switch"), 1e-6)
        assertNull(judgement.exclusions["switch"])
    }

    @Test
    fun `a worthless Pokemon may take the knockout meant for the only answer`() {
        // Only the ally beats the benched foe; the active foe is about to knock it out. The bench beats nobody.
        val context = context(allySpeed = 100, foeSpeed = 150, allyPower = 300.0, benchPower = 40.0, foePower = 300.0,
            secondFoe = true)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        assertEquals(listOf(FOE2), scores.preserves.getValue(ALLY).soleAnswerTo)
        assertEquals(0.0, scores.preserves.getValue(BENCH).score, 1e-9)
        val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, scores)
        assertNull(judgement.exclusions["switch"]) { "the sacrifice dies on entry by design" }
        assertTrue(judgement.adjustments.getValue("switch") > 0.0)
    }

    @Test
    fun `a slow pivot brings the bench in free and is credited above the hard switch`() {
        // The slower weak ally loses staying. After the foe's hit on it, the bench walks in without one and wins.
        val context = context(allySpeed = 100, foeSpeed = 150, allyPower = 40.0, benchPower = 300.0, foePower = 65.0,
            allyExtra = listOf(uturn()))
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val judgement = LocalSwitchRules.judge(listOf(pivotAction(context), switchAction()), context, scores)
        val free = scores.pokemon(BENCH, FOE)!!.score - scores.pokemon(ALLY, FOE)!!.score
        assertEquals(free * LocalSwitchRules.SCORE_SCALE, judgement.adjustments.getValue("pivot"), 1e-6)
        assertTrue(judgement.adjustments.getValue("pivot") > (judgement.adjustments["switch"] ?: 0.0)) { judgement.toString() }
    }

    @Test
    fun `a mid-turn replacement is never ruled out for the hit it would take`() {
        // A pivot's replacement: the leaving ally still stands, and the foe knocks out anything that walks in.
        for (foeMoved in listOf(true, false)) {
            val context = context(allySpeed = 150, foeSpeed = 100, foeMovedThisTurn = foeMoved)
            val judgement = LocalSwitchRules.judge(listOf(switchAction()), context, LocalMatchupScoreCalculator.calculate(context))
            assertTrue(judgement.exclusions.isEmpty()) { "foe moved $foeMoved: $judgement" }
        }
    }

    private fun uturn() = BattlePublicMoveOptionView("uturn", BattleMoveCandidateView(typeId = "bug",
        damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 20.0, accuracy = 100.0, priority = 0, currentPp = 8,
        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
            BattleMoveEffectKind.SWITCH_USER, BattleMoveEffectTarget.USER)), false)), BattlePublicMoveKnowledge.EXACT_OWN)

    private fun pivotAction(context: BattleDecisionContext): BattleActionCandidate {
        val option = context.publicActionCatalog.forPokemon(ALLY).first { it.moveId == "uturn" }
        return BattleActionCandidate("pivot", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = option.moveId, moveDetails = option.details)
    }

    private fun attackAction() = BattleActionCandidate("attack", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "probe")

    private fun switchAction() = BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = BENCH)

    private fun setupAction(context: BattleDecisionContext): BattleActionCandidate {
        val option = context.publicActionCatalog.forPokemon(ALLY).first { it.details.damageCategory == BattleMoveDamageCategory.STATUS }
        return BattleActionCandidate("setup", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = option.moveId, moveDetails = option.details)
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
        benchPower: Double? = null,
        foeCategory: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        /** A benched opponent, slow and three-hitting, that only a strong ally beats. */
        secondFoe: Boolean = false,
        format: BattleFormat = BattleFormat.SINGLE,
        foeMovedThisTurn: Boolean = false,
        /** The bench Pokemon stands beside the ally, in doubles. */
        benchActive: Boolean = false,
        foeStatus: String? = null,
        /** The second opponent stands beside the first, in doubles. */
        secondFoeActive: Boolean = false,
        events: List<BattleObservedEventView> = emptyList(),
        /** Moves for the benched second opponent only, in place of [foeExtra]. */
        benchFoeExtra: List<BattlePublicMoveOptionView>? = null,
    ): BattleDecisionContext {
        val field = if (!rocksOnAllySide) BattleFieldStateView.empty() else BattleFieldStateView(null, null, emptyList(), emptyList(),
            mapOf(BattleSide.ALLY to listOf(BattleTimedEffectView("stealthrock", null)), BattleSide.OPPONENT to emptyList()))
        val observed = events + if (!foeMovedThisTurn) emptyList() else
            listOf(BattleObservedEventView(9, 1, BattleObservedEventKind.MOVE_USED, FOE, listOf(ALLY), "probe"))
        val state = BattleStateView(UUID(0, 918), format, 1,
            listOf(pokemon(ALLY, BattleSide.ALLY, 0, allySpeed, allyItem), pokemon(BENCH, BattleSide.ALLY, if (benchActive) 1 else null, allySpeed),
                pokemon(FOE, BattleSide.OPPONENT, 0, foeSpeed, status = foeStatus)) + listOfNotNull(if (secondFoe) pokemon(FOE2, BattleSide.OPPONENT, if (secondFoeActive) 1 else null, 50) else null),
            field, mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to if (secondFoe) 2 else 1), observed, emptyList())
        return BattleDecisionContext(UUID(0, 919), state, listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(state.pokemon.map { pokemon ->
                val power = when (pokemon.battlePokemonId) { BENCH -> benchPower ?: allyPower; FOE -> foePower; FOE2 -> 65.0; else -> allyPower }
                val category = if (pokemon.side == BattleSide.ALLY) BattleMoveDamageCategory.PHYSICAL else foeCategory
                val knowledge = if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED
                val attack = BattlePublicMoveOptionView("probe", BattleMoveCandidateView(typeId = "normal",
                    damageCategory = category, power = power, accuracy = 100.0, priority = 0, currentPp = 8), knowledge)
                val setup = (if (pokemon.side == BattleSide.ALLY) allySetup else foeSetup)?.let { stages ->
                    BattlePublicMoveOptionView(if (stages.size > 1) "dragondance" else "swordsdance", BattleMoveCandidateView(typeId = "normal",
                        damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
                        targetPattern = BattleMoveTargetPattern.SELF,
                        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                            BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = stages)), false)), knowledge)
                }
                val extra = when {
                    pokemon.side == BattleSide.ALLY -> allyExtra
                    pokemon.battlePokemonId == FOE2 && benchFoeExtra != null -> benchFoeExtra
                    else -> foeExtra
                }
                BattlePokemonActionCatalogView(pokemon.battlePokemonId, listOfNotNull(attack, setup) + extra, moveSetComplete = true)
            }))
    }

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, speed: Int, item: String? = null, status: String? = null) =
        BattlePokemonStateView(id, side, slot, "fixture:matchup", null, 50, 1.0, status, emptyMap(), emptySet(), null, item, false,
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
        val FOE2 = UUID(0, 4)
    }
}
