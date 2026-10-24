package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth
import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalActingChanceTest {
    @Test
    fun `first hit option preserves acting probability and trace including unresolved intent fallback`() {
        val tuning = jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT
        for (f in listOf(fixture(), fixture(accuracy = 73.0), fixture(ownPriority = 1), fixture(foeStatus = "par"),
            fixture(lightAttack = true), fixture(foeMove = "superpower"), fixture(foeMove = "suckerpunch"))) {
            for (max in listOf(false, true)) for (kind in listOf("known", "unknown", "status", "missing")) {
                val intents = when (kind) {
                    "missing" -> null
                    "status" -> listOf(f.option(IntentKind.STATUS, 1.0))
                    "unknown" -> listOf(f.option(IntentKind.ATTACK, 0.7, "unresolved"), f.option(IntentKind.SWITCH, 0.3))
                    else -> listOf(f.option(IntentKind.ATTACK, 0.7), f.option(IntentKind.PROTECT, 0.3))
                }?.let { listOf(OpponentIntent(f.foe.battlePokemonId, 0, it)) }
                val board = f.context.withDynamaxInBattle(max)
                fun read(enabled: Boolean): Pair<Double, Map<String, Map<String, Double>>> {
                    LocalDecisionInternals.begin()
                    try {
                        val value = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.with(tuning.copy(firstHitActingChance = enabled)) {
                            LocalActingChance.of(f.action, board, scores, intents = intents)
                        }
                        return value to LocalDecisionInternals.take()
                    } finally { LocalDecisionInternals.clear() }
                }
                val before = read(false); val after = read(true)
                assertEquals(before.first.toBits(), after.first.toBits(), "$kind/max=$max/${f.foeMove}")
                assertEquals(before.second, after.second, "$kind/max=$max/${f.foeMove}")
            }
        }
    }

    @Test
    fun `joint turns reuse an identical part while replaying each candidates evidence`() {
        val f = fixture()
        val partner = mon(BattleSide.ALLY, 100).copyState(activeSlot = 1)
        val state = BattleStateView(f.context.state.battleId, BattleFormat.DOUBLE, 4,
            listOf(f.user, partner, f.foe), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val context = f.context.copy(state = state)
        fun turn(id: String) = BattleActionCandidate(id, BattleActionKind.COMPOSITE,
            componentActionIds = listOf(f.action.actionId, "partner:$id"),
            componentActions = listOf(f.action, BattleActionCandidate("partner:$id", BattleActionKind.WAIT, actorSlot = 1)))
        val intents = listOf(OpponentIntent(f.foe.battlePokemonId, 0, listOf(f.option(IntentKind.ATTACK, 1.0))))
        val cache = LocalProjectedActionCalculationCache()
        fun read(id: String, ignoring: Set<UUID> = emptySet()): Pair<Double, Map<String, Map<String, Double>>> {
            LocalDecisionInternals.begin()
            try {
                val value = LocalActingChance.of(turn(id), context, scores, 0, ignoring, intents, cache)
                return value to LocalDecisionInternals.take()
            } finally { LocalDecisionInternals.clear() }
        }
        LocalDecisionCalculationScope.with { scope ->
            val first = read("turn:a")
            val hits = scope.hits
            val second = read("turn:b")
            assertEquals(first.first, second.first)
            assertEquals(hits + 1, scope.hits)
            assertEquals(first.second.mapKeys { (key, _) -> key.replace("turn:a", "turn:b") }, second.second)
            assertEquals(1.0, read("turn:c", setOf(f.foe.battlePokemonId)).first)
            val fast = BattleActionCandidate(f.action.actionId, BattleActionKind.USE_MOVE, 0, 0,
                f.action.moveId, moveDetails = f.action.moveDetails!!.copy(priority = 1))
            assertEquals(1.0, LocalActingChance.of(fast, context, scores, 0, intents = intents, calculationCache = cache))
            val partnerMax = BattleActionCandidate("partner:max", BattleActionKind.USE_MOVE, 1, 0,
                "swordsdance", moveDetails = f.action.moveDetails, mechanic = BattleMechanicCandidate("dynamax", null, 1))
            val maxTurn = BattleActionCandidate("turn:max", BattleActionKind.COMPOSITE,
                componentActionIds = listOf(f.action.actionId, partnerMax.actionId), componentActions = listOf(f.action, partnerMax))
            val priorMisses = scope.misses
            assertEquals(0.0, LocalActingChance.of(maxTurn, context, scores, 0, intents = intents, calculationCache = cache))
            org.junit.jupiter.api.Assertions.assertTrue(scope.misses > priorMisses)

            val miss = scope.misses
            val changed = context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
                if (it.battlePokemonId == f.foe.battlePokemonId) it.copyState(statusId = "par") else it }))
            assertEquals(0.25, LocalActingChance.of(turn("turn:d"), changed, scores, 0, intents = intents, calculationCache = cache), 1e-9)
            org.junit.jupiter.api.Assertions.assertTrue(scope.misses > miss)
            val otherIntent = listOf(OpponentIntent(f.foe.battlePokemonId, 0, listOf(f.option(IntentKind.SWITCH, 1.0))))
            assertEquals(1.0, LocalActingChance.of(turn("turn:e"), context, scores, 0, intents = otherIntent, calculationCache = cache))
        }
    }

    @Test
    fun `a certain faster knockout remains zero when that attack is certain`() {
        val f = fixture()
        assertEquals(0.0, f.chance(listOf(f.option(IntentKind.ATTACK, 1.0))), 1e-9)
    }

    @Test
    fun `a switch share preserves each move's chance to act`() {
        val f = fixture()
        assertEquals(0.3, f.chance(listOf(f.option(IntentKind.ATTACK, 0.7), f.option(IntentKind.SWITCH, 0.3))), 1e-9)
    }

    @Test
    fun `status and protection choices are not treated as attacks`() {
        val f = fixture()
        assertEquals(0.6, f.chance(listOf(f.option(IntentKind.ATTACK, 0.4), f.option(IntentKind.SETUP, 0.3),
            f.option(IntentKind.PROTECT, 0.2), f.option(IntentKind.STATUS, 0.1))), 1e-9)
    }

    @Test
    fun `the chosen attacks own knockout chances are averaged rather than the best hit assigned all mass`() {
        val f = fixture(lightAttack = true)
        assertEquals(0.75, f.chance(listOf(f.option(IntentKind.ATTACK, 0.25),
            f.option(IntentKind.ATTACK, 0.75, "pound"))), 1e-9)
    }

    @Test
    fun `priority changes the actual order without an added probability bonus`() {
        val f = fixture(ownPriority = 1)
        assertEquals(1.0, f.chance(listOf(f.option(IntentKind.ATTACK, 1.0))), 1e-9)
        val fasterPriority = fixture(ownPriority = 1, foePriority = 2)
        assertEquals(0.0, fasterPriority.chance(listOf(fasterPriority.option(IntentKind.ATTACK, 1.0))), 1e-9)
    }

    @Test
    fun `accuracy and the opponents paralysis are each counted once`() {
        val inaccurate = fixture(accuracy = 50.0)
        assertEquals(0.5, inaccurate.chance(listOf(inaccurate.option(IntentKind.ATTACK, 1.0))), 1e-9)
        val paralyzed = fixture(foeStatus = "par")
        assertEquals(1.0 - 0.7 * 0.75, paralyzed.chance(listOf(paralyzed.option(IntentKind.ATTACK, 0.7),
            paralyzed.option(IntentKind.SWITCH, 0.3))), 1e-9)
    }

    @Test
    fun `a known different target contributes no incoming hit`() {
        val f = fixture()
        val elsewhere = f.option(IntentKind.ATTACK, 1.0).copy(targetId = UUID.randomUUID())
        assertEquals(1.0, f.chance(listOf(elsewhere)), 1e-9)
    }

    @Test
    fun `missing intent and unresolvable attack keep a conservative fallback`() {
        val f = fixture()
        assertEquals(0.0, LocalActingChance.of(f.action, f.context, scores), 1e-9)
        assertEquals(0.3, f.chance(listOf(f.option(IntentKind.ATTACK, 0.7, "unreadattack"),
            f.option(IntentKind.SWITCH, 0.3))), 1e-9)
    }

    @Test
    fun `a pending-attack requirement cannot stop a known status candidate`() {
        val f = fixture(foeMove = "suckerpunch", foePriority = 1)
        assertEquals(1.0, f.chance(listOf(f.option(IntentKind.ATTACK, 1.0, "suckerpunch"))), 1e-9)
    }

    @Test
    fun `Trick Room uses the same forecast despite its negative priority`() {
        val f = fixture(ownPriority = -7)
        val intents = listOf(OpponentIntent(f.foe.battlePokemonId, 0, listOf(f.option(IntentKind.ATTACK, 0.7),
            f.option(IntentKind.SWITCH, 0.3))))
        assertEquals(0.3, LocalTrickRoomCredit.landing(f.action, f.context, scores, intents), 1e-9)
    }

    @Test
    fun `two nonlethal attacks combine only when both are selected`() {
        val hit = hit(0.6, 0.0)
        val first = listOf(LocalActingChance.WeightedHit(0.7, hit), LocalActingChance.WeightedHit(0.3, null))
        val second = listOf(LocalActingChance.WeightedHit(0.6, hit), LocalActingChance.WeightedHit(0.4, null))
        assertEquals(0.42, LocalActingChance.expectedKnockout(listOf(first, second), 1.0), 1e-9)
    }

    @Test
    fun `two foes cannot both Dynamax in the same combined hit`() {
        val choice = LocalActingChance.WeightedHit(1.0, hit(0.1, 0.0), hit(0.6, 0.0))
        assertEquals(0.0, LocalActingChance.expectedKnockout(listOf(listOf(choice), listOf(choice)), 1.0), 1e-9)
    }

    @Test
    fun `KO material uses the forecast too and clearing the decision clears it`() {
        val f = fixture()
        val target = f.foe.battlePokemonId
        val denial = LocalKnockoutWorth.Denial(0.0, 1, threat = 1.0,
            threatOn = mapOf(f.user.battlePokemonId to 1.0))
        try {
            LocalPokemonWorth.set(mapOf(target to 2.0), mapOf(target to denial))
            assertEquals(0.0, LocalPokemonWorth.knockoutScore(target, LocalDecisionTuning.CURRENT, f.action,
                f.context.state, 0.0), 1e-9)
            LocalPokemonWorth.setActingChance { _, _, _ -> 0.3 }
            assertEquals(60.0, LocalPokemonWorth.knockoutScore(target, LocalDecisionTuning.CURRENT, f.action,
                f.context.state, 0.0), 1e-9)
            LocalPokemonWorth.clear()
            assertEquals(200.0, LocalPokemonWorth.knockoutScore(target, LocalDecisionTuning.CURRENT, f.action,
                f.context.state, 0.0), 1e-9)
        } finally { LocalPokemonWorth.clear() }
    }

    @Test
    fun `simple model eliminates tactical roll and intent product work with status and priority retained`() {
        val audit = LocalHpMatchupAudit
        for (f in listOf(fixture(), fixture(ownPriority = 1), fixture(foeMove = "suckerpunch", foePriority = 1), fixture(accuracy = 73.0))) {
            val table = LocalMatchupScoreCalculator.calculate(f.context)
            for (kind in listOf(IntentKind.ATTACK, IntentKind.STATUS, IntentKind.SWITCH)) {
                val intents = listOf(OpponentIntent(f.foe.battlePokemonId, 0, listOf(f.option(kind, 0.7), f.option(IntentKind.PROTECT, 0.3))))
                val expected = LocalActingChance.of(f.action, f.context, table, intents = intents)
                audit.begin()
                val actual = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.with(LocalDecisionTuning.CURRENT.copy(simpleActingChance = true)) {
                    LocalActingChance.of(f.action, f.context, table, intents = intents)
                }
                val work = audit.take()
                assertEquals(expected, actual, 1e-9, "$kind/${f.foeMove}")
                assertEquals(0, work.tacticalCalls); assertEquals(0, work.rollCalculations)
                assertEquals(0, work.actingChoiceProducts); assertEquals(1, work.simpleActingModels)
            }
        }
    }

    @Test
    fun `simple max interval retains known first hit shields without full damage calculation`() {
        val f = fixture()
        for ((ability, item) in listOf("sturdy" to "", "disguise" to "", "" to "focussash")) {
            val context = f.context.copy(state = f.context.state.copyState(pokemon = f.context.state.pokemon.map {
                if (it.battlePokemonId == f.user.battlePokemonId) it.copyState(knownAbilityId = ability, knownHeldItemId = item) else it
            }))
            val table = LocalMatchupScoreCalculator.calculate(context)
            val move = table.moves(f.foe.battlePokemonId, f.user.battlePokemonId).single()
            LocalHpMatchupAudit.begin()
            val hit = LocalSimpleMatchup.hit(context, context, move, max = true)
            val work = LocalHpMatchupAudit.take()
            assertEquals(0.0, hit.move.knockoutChanceWithin(1), 1e-12, "$ability/$item")
            assertTrue(hit.move.maximumDamageFraction < 1.0)
            assertEquals(0, work.tacticalCalls); assertEquals(0, work.rollCalculations)
        }
    }

    @Test
    fun `simple joint hit retains two nonlethal attacks misses and HP-setting exclusion`() {
        val a = LocalSimpleActingChance.Interval(0.7, 0.6, 0.6, 0.0)
        val b = LocalSimpleActingChance.Interval(0.6, 0.6, 0.6, 0.0)
        assertEquals(0.42, LocalSimpleActingChance.knockout(listOf(a, b), 1.0), 1e-12)
        assertEquals(0.0, LocalSimpleActingChance.knockout(listOf(a.copy(combines = false), b), 1.0), 1e-12)
        assertEquals(0.0, LocalSimpleActingChance.knockout(listOf(a.copy(low = 0.1, high = 0.1), b.copy(low = 0.1, high = 0.1)), 1.0), 1e-12)
        assertEquals(0.7, LocalSimpleActingChance.knockout(listOf(a.copy(ko = 0.7)), 1.0), 1e-12)
    }

    @Test
    fun `inferred and pool move metadata retain spread targeting without rebuilding attacks`() {
        val f = fixture(foeMove = "earthquake")
        val partner = mon(BattleSide.ALLY, 100).copyState(activeSlot = 1)
        val state = BattleStateView(f.context.state.battleId, BattleFormat.DOUBLE, 4, listOf(f.user, partner, f.foe),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val details = f.context.publicActionCatalog.forPokemon(f.foe.battlePokemonId).single().details.copy(targetPattern = BattleMoveTargetPattern.ALL_ADJACENT)
        val own = f.context.publicActionCatalog.entries.filter { it.battlePokemonId != f.foe.battlePokemonId }
        val known = BattlePublicActionCatalogView(own + BattlePokemonActionCatalogView(f.foe.battlePokemonId,
            listOf(BattlePublicMoveOptionView("earthquake", details, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), true))
        val context = f.context.copy(state = state, publicActionCatalog = known)
        val table = LocalMatchupScoreCalculator.calculate(context)
        val inferred = BattleOpponentMoveInferenceView(f.foe.battlePokemonId, listOf(BattleOpponentMoveSlotView(0,
            "earthquake", BattleOpponentMoveGroup.COVERAGE_ATTACK, BattleOpponentMoveKnowledge.EXPECTED,
            BattleOpponentMoveSource.LEARNSET_EXPECTATION, details)))
        val pool = BattlePublicMoveCandidatePoolView(f.foe.battlePokemonId, f.foe.speciesId, f.foe.formId,
            setOf("earthquake"), "test", mapOf("earthquake" to details))
        for (catalog in listOf(BattlePublicActionCatalogView(own, opponentMoveInferences = listOf(inferred)),
            BattlePublicActionCatalogView(own, candidatePools = listOf(pool)))) {
            val board = context.copy(publicActionCatalog = catalog)
            val intents = listOf(OpponentIntent(f.foe.battlePokemonId, 0,
                listOf(IntentOption(IntentKind.ATTACK, "earthquake", null, null, 0.0, 1.0))))
            LocalHpMatchupAudit.begin()
            val value = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.with(LocalDecisionTuning.CURRENT.copy(simpleActingChance = true)) {
                LocalActingChance.of(f.action, board, table, intents = intents)
            }
            val work = LocalHpMatchupAudit.take()
            assertEquals(0.0, value, 1e-12, "spread attack reaches this slot with probability one")
            assertEquals(0, work.tacticalCalls); assertEquals(0, work.rollCalculations)
        }
    }

    @Test
    fun `two representative bins retain dangerous choices hidden by their mean`() {
        val steady = listOf(LocalSimpleActingChance.Interval(1.0, .6, .6, 0.0))
        val mixture = listOf(LocalSimpleActingChance.Interval(.5, .1, .1, 0.0),
            LocalSimpleActingChance.Interval(.5, .6, .6, 0.0))
        assertEquals(.5, LocalSimpleActingChance.groupedKnockout(listOf(steady, mixture), 1.0), 1e-12)
        assertEquals(.35, LocalSimpleActingChance.groupedKnockout(listOf(steady.map { it.copy(lands = .7) }, mixture), 1.0), 1e-12)
        assertEquals(0.0, LocalSimpleActingChance.knockout(listOf(steady.single(),
            LocalSimpleActingChance.Interval(1.0, .35, .35, 0.0)), 1.0), 1e-12)
    }

    @Test
    fun `constant uniform convolution agrees with independent numerical integration`() {
        for ((a, b, c, d) in listOf(listOf(0.1, 0.6, 0.2, 0.8), listOf(0.4, 0.5, 0.1, 0.9))) {
            for (hp in listOf(0.1, 0.4, 0.7, 1.0, 1.4)) {
                val numerical = (0 until 10000).sumOf { step ->
                    val first = a + (b - a) * (step + .5) / 10000
                    ((hp - first - c) / (d - c)).coerceIn(0.0, 1.0)
                } / 10000
                assertEquals(numerical, LocalSimpleMatchup.sumBelow(a, b, c, d, hp), 1e-7)
            }
        }
        assertEquals(0.0, LocalSimpleMatchup.sumBelow(.6, .6, .4, .4, 1.0))
        assertEquals(1.0, LocalSimpleMatchup.sumBelow(.6, .6, .4, .4, 1.01))
    }

    private data class Fixture(val user: BattlePokemonStateView, val foe: BattlePokemonStateView,
        val action: BattleActionCandidate, val context: BattleDecisionContext, val foeMove: String) {
        fun option(kind: IntentKind, chance: Double, id: String? = if (kind == IntentKind.ATTACK) foeMove else null) =
            IntentOption(kind, id, if (kind == IntentKind.ATTACK) user.battlePokemonId else null, null, 0.0, chance)
        fun chance(options: List<IntentOption>) = LocalActingChance.of(action, context, scores,
            intents = listOf(OpponentIntent(foe.battlePokemonId, 0, options)))
    }

    private fun fixture(ownPriority: Int = 0, foePriority: Int = 0, accuracy: Double = 100.0,
        foeStatus: String? = null, lightAttack: Boolean = false, foeMove: String = "tackle"): Fixture {
        val user = mon(BattleSide.ALLY, 100)
        val foe = mon(BattleSide.OPPONENT, 400, foeStatus)
        val details = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0,
            ownPriority, 8, BattleMoveTargetPattern.SELF,
            effects = BattleShowdownMoveEffects.resolve("swordsdance"))
        val action = BattleActionCandidate("setup", BattleActionKind.USE_MOVE, 0, 0, "swordsdance", moveDetails = details)
        val attack = BattlePublicMoveOptionView(foeMove, BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
            100.0, accuracy, foePriority, 8, effects = BattleShowdownMoveEffects.resolve(foeMove)), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        val light = BattlePublicMoveOptionView("pound", attack.details.copy(power = 1.0), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        val catalog = BattlePublicActionCatalogView(listOf(
            BattlePokemonActionCatalogView(user.battlePokemonId, listOf(BattlePublicMoveOptionView("swordsdance", details,
                BattlePublicMoveKnowledge.EXACT_OWN)), true),
            BattlePokemonActionCatalogView(foe.battlePokemonId, listOf(attack) + if (lightAttack) listOf(light) else emptyList(), true)))
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 4, listOf(user, foe), BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        return Fixture(user, foe, action, BattleDecisionContext(UUID.randomUUID(), state, listOf(action), Long.MAX_VALUE,
            publicActionCatalog = catalog).withDynamaxInBattle(false), foeMove)
    }

    private fun mon(side: BattleSide, speed: Int, status: String? = null) = BattlePokemonStateView(
        UUID.randomUUID(), side, 0, "fixture:acting", null, 50, 1.0, status, emptyMap(), emptySet(), "", "", false,
        knownTypeIds = setOf("normal"), combatStats = BattleCombatStatRangesView(
            BattleIntegerRange(100, 100), BattleIntegerRange(300, 300), BattleIntegerRange(60, 60),
            BattleIntegerRange(100, 100), BattleIntegerRange(60, 60), BattleIntegerRange(speed, speed),
            if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.LOCAL_OPPONENT_ESTIMATE))

    private fun hit(damage: Double, knockout: Double) = LocalDynamaxGuard.IncomingHit(
        MoveMatchupScore(UUID.randomUUID(), "probe", UUID.randomUUID(), 1.0, damage, damage,
            listOf(1.0, 1.0 - knockout), listOf(0.0, damage), 2.0, 0.5), 1.0, 1.0)

    private companion object { val scores = MatchupScores(emptyMap(), emptyMap(), complete = true) }
}
