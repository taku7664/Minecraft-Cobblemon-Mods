package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalContactAfterHitBranch
import jbro.cobblemon.mcc.betterai.mechanics.LocalContactAfterHitMechanics
import jbro.cobblemon.mcc.betterai.mechanics.RecursiveControlEffectKind
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.betterai.state.PublicTurnProjection
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import jbro.cobblemon.mcc.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Current server items.js cures both status and confusion when a publicly active Lum Berry is eaten. */
class LocalPublicStatusItemPathTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `Flame Body consumes Lum only on the status branch and leaves no burn`() {
        val actor = mon(BattleSide.ALLY, item = "lumberry")
        val target = mon(BattleSide.OPPONENT, ability = "flamebody")
        val branches = contact(actor, target)
        assertEquals(0.3, branches.filter { holder(it.state, actor).knownHeldItemId == "" }.sumOf { it.probability }, 1e-9)
        assertTrue(branches.all { holder(it.state, actor).statusId == null })
        assertEquals(1.0, branches.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun `Poison Touch can consume the target Lum and cure its poison`() {
        val actor = mon(BattleSide.ALLY, ability = "poisontouch")
        val target = mon(BattleSide.OPPONENT, item = "lumberry")
        val branches = contact(actor, target)
        assertEquals(0.3, branches.filter { holder(it.state, target).knownHeldItemId == "" }.sumOf { it.probability }, 1e-9)
        assertTrue(branches.all { holder(it.state, target).statusId == null })
    }

    @Test
    fun `Lum eating for contact status also removes an existing confusion`() {
        val actor = mon(BattleSide.ALLY, item = "lumberry").copyState(knownVolatileEffectIds = setOf("confusion"))
        val branches = contact(actor, mon(BattleSide.OPPONENT, ability = "static"))
        val eaten = branches.filter { holder(it.state, actor).knownHeldItemId == "" }
        assertFalse(eaten.isEmpty(), "Static's real status branch must eat the berry")
        assertTrue(eaten.all { "confusion" !in holder(it.state, actor).canonicalKnownVolatileEffectIds })
    }

    @Test
    fun `an immune contact attacker keeps its Lum`() {
        val actor = mon(BattleSide.ALLY, ability = "waterveil", item = "lumberry")
        assertTrue(contact(actor, mon(BattleSide.OPPONENT, ability = "flamebody")).all {
            holder(it.state, actor).statusId == null && holder(it.state, actor).knownHeldItemId == "lumberry"
        })
    }

    @Test
    fun `Klutz and Magic Room suppress contact Lum without curing the inflicted status`() {
        val target = mon(BattleSide.OPPONENT, ability = "static")
        val klutz = mon(BattleSide.ALLY, ability = "klutz", item = "lumberry")
        val plain = mon(BattleSide.ALLY, item = "lumberry")
        listOf(klutz to contact(klutz, target), plain to contact(plain, target, magicRoom(fixture.state(plain, target)))).forEach { (actor, branches) ->
            assertEquals(0.3, branches.filter { holder(it.state, actor).statusId != null }.sumOf { it.probability }, 1e-9)
            assertTrue(branches.all { holder(it.state, actor).knownHeldItemId == "lumberry" })
        }
    }

    @Test
    fun `an opposing Unnerve prevents a contact Lum from being eaten`() {
        val actor = mon(BattleSide.ALLY, item = "lumberry")
        val target = mon(BattleSide.OPPONENT, ability = "flamebody")
        val unnerve = fixture.mon(BattleSide.OPPONENT, 1, ability = "unnerve")
        val branches = contact(actor, target, fixture.state(actor, target, unnerve, format = BattleFormat.DOUBLE))
        assertEquals(0.3, branches.filter { holder(it.state, actor).statusId != null }.sumOf { it.probability }, 1e-9)
        assertTrue(branches.all { holder(it.state, actor).knownHeldItemId == "lumberry" })
    }

    @Test
    fun `Safeguard blocks Flame Body and Infiltrator cannot bypass an ability callback`() {
        val target = mon(BattleSide.OPPONENT, ability = "flamebody")
        listOf(null, "infiltrator").forEach { ability ->
            val actor = mon(BattleSide.ALLY, ability = ability)
            val branches = contact(actor, target, safeguard(fixture.state(actor, target), BattleSide.ALLY))
            assertTrue(branches.all { holder(it.state, actor).statusId == null }, "Contact status through Safeguard: $ability")
        }
    }

    @Test
    fun `orb statuses respect the known Water Veil and Immunity abilities`() {
        listOf("waterveil" to "flameorb", "immunity" to "toxicorb").forEach { (ability, item) ->
            val actor = mon(BattleSide.ALLY, ability = ability, item = item)
            assertNull(holder(endTurn(actor), actor).statusId, "$ability must prevent $item")
        }
    }

    @Test
    fun `Purifying Salt and Comatose prevent either orb status`() {
        listOf("purifyingsalt", "comatose").forEach { ability ->
            listOf("flameorb", "toxicorb").forEach { item ->
                val actor = mon(BattleSide.ALLY, ability = ability, item = item)
                assertNull(holder(endTurn(actor), actor).statusId, "$ability must prevent $item")
            }
        }
    }

    @Test
    fun `a grounded orb holder is protected by Misty Terrain but an airborne holder is not`() {
        val grounded = mon(BattleSide.ALLY, item = "flameorb")
        val airborne = mon(BattleSide.ALLY, item = "flameorb").copyState(knownTypeIds = setOf("flying"))
        assertNull(holder(LocalEndTurnStateProjector.project(terrain(fixture.state(grounded), "mistyterrain")), grounded).statusId)
        assertEquals("brn", holder(LocalEndTurnStateProjector.project(terrain(fixture.state(airborne), "mistyterrain")), airborne).statusId)
    }

    @Test
    fun `Misty Terrain expires before an orb tries to set its status`() {
        val actor = mon(BattleSide.ALLY, item = "flameorb")
        val result = LocalEndTurnStateProjector.project(terrain(fixture.state(actor), "mistyterrain", 1))
        assertEquals("brn", holder(result, actor).statusId)
    }

    @Test
    fun `Leaf Guard checks the weather still active when an orb fires`() {
        val actor = mon(BattleSide.ALLY, ability = "leafguard", item = "toxicorb")
        assertNull(holder(LocalEndTurnStateProjector.project(weather(fixture.state(actor), "sunnyday", 3)), actor).statusId)
        assertEquals("tox", holder(LocalEndTurnStateProjector.project(weather(fixture.state(actor), "sunnyday", 1)), actor).statusId)
    }

    @Test
    fun `an orb cannot give status to a holder already knocked out by residual damage`() {
        val actor = mon(BattleSide.ALLY, item = "flameorb").copyState(hpFraction = 1.0 / 160.0)
        val result = holder(LocalEndTurnStateProjector.project(weather(fixture.state(actor), "sandstorm", 3)), actor)
        assertTrue(result.fainted)
        assertNull(result.statusId)
    }

    @Test
    fun `orb self status ignores Safeguard but remains suppressed by Klutz and Magic Room`() {
        val actor = mon(BattleSide.ALLY, item = "toxicorb")
        assertEquals("tox", holder(LocalEndTurnStateProjector.project(safeguard(fixture.state(actor), BattleSide.ALLY)), actor).statusId)
        val klutz = actor.copyState(knownAbilityId = "klutz")
        assertNull(holder(endTurn(klutz), klutz).statusId)
        assertNull(holder(LocalEndTurnStateProjector.project(magicRoom(fixture.state(actor))), actor).statusId)
    }

    @Test
    fun `Confuse Ray consumes Lum and does not leave a recursive confusion effect`() {
        val actor = mon(BattleSide.ALLY, speed = 200)
        val target = mon(BattleSide.OPPONENT, item = "lumberry", speed = 50)
        val outcomes = effectTurn(fixture.state(actor, target), move("confuseray", "confusion"))
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { holder(it.state, target).knownHeldItemId == "" })
        assertTrue(outcomes.none { it.controlEffects.any { effect -> effect.kind == RecursiveControlEffectKind.CONFUSION } })
    }

    @Test
    fun `Lum eating for new confusion cures the existing major status too`() {
        val actor = mon(BattleSide.ALLY, speed = 200)
        val target = mon(BattleSide.OPPONENT, item = "lumberry", speed = 50).copyState(statusId = "psn")
        val outcomes = effectTurn(fixture.state(actor, target), move("confuseray", "confusion"))
        assertTrue(outcomes.all { holder(it.stateBeforeResidual, target).statusId == null })
        assertTrue(outcomes.all { holder(it.state, target).knownHeldItemId == "" })
    }

    @Test
    fun `suppressed Lum permits confusion and keeps the berry`() {
        val actor = mon(BattleSide.ALLY, speed = 200)
        val target = mon(BattleSide.OPPONENT, ability = "klutz", item = "lumberry", speed = 50)
        val outcomes = effectTurn(fixture.state(actor, target), move("confuseray", "confusion"))
        assertTrue(outcomes.all { holder(it.state, target).knownHeldItemId == "lumberry" })
        assertTrue(outcomes.all { it.controlEffects.any { effect -> effect.kind == RecursiveControlEffectKind.CONFUSION } })
    }

    @Test
    fun `Unnerve blocks the move status Lum cure as well as the confusion cure`() {
        val actor = mon(BattleSide.ALLY, ability = "unnerve", speed = 200)
        val target = mon(BattleSide.OPPONENT, item = "lumberry", speed = 50)
        val outcomes = effectTurn(fixture.state(actor, target), move("thunderwave", "par", BattleMoveEffectKind.STATUS))
        assertTrue(outcomes.all { holder(it.stateBeforeResidual, target).statusId == "par" })
        assertTrue(outcomes.all { holder(it.state, target).knownHeldItemId == "lumberry" })
        val confused = effectTurn(fixture.state(actor, target), move("confuseray", "confusion"))
        assertTrue(confused.all { it.controlEffects.any { effect -> effect.kind == RecursiveControlEffectKind.CONFUSION } })
        assertTrue(confused.all { holder(it.state, target).knownHeldItemId == "lumberry" })
    }

    @Test
    fun `Safeguard blocks a new confusion and a new Yawn`() {
        val actor = mon(BattleSide.ALLY, speed = 200)
        val target = mon(BattleSide.OPPONENT, speed = 50)
        val state = safeguard(fixture.state(actor, target), BattleSide.OPPONENT)
        listOf("confusion" to RecursiveControlEffectKind.CONFUSION, "yawn" to RecursiveControlEffectKind.YAWN).forEach { (volatile, kind) ->
            assertTrue(effectTurn(state, move(if (volatile == "confusion") "confuseray" else "yawn", volatile)).none {
                it.controlEffects.any { effect -> effect.kind == kind }
            }, "$volatile must fail through Safeguard")
        }
    }

    @Test
    fun `Misty Terrain blocks confusion from a move affecting its own grounded user`() {
        val actor = mon(BattleSide.ALLY)
        val target = mon(BattleSide.OPPONENT)
        val outcomes = effectTurn(terrain(fixture.state(actor, target), "mistyterrain"),
            move("petaldance", "confusion", target = BattleMoveEffectTarget.USER))
        assertTrue(outcomes.none { it.controlEffects.any { effect -> effect.kind == RecursiveControlEffectKind.CONFUSION } })
    }

    @Test
    fun `Yawn falling due consumes Lum and leaves its holder awake`() {
        val actor = mon(BattleSide.ALLY, item = "lumberry")
        val result = LocalEndTurnStateProjector.project(fixture.state(actor), yawnPokemonIds = setOf(actor.battlePokemonId))
        assertNull(holder(result, actor).statusId)
        assertEquals("", holder(result, actor).knownHeldItemId)
    }

    @Test
    fun `a pending active Lum update cures status and confusion before residual damage`() {
        val actor = mon(BattleSide.ALLY, item = "lumberry").copyState(statusId = "psn", knownVolatileEffectIds = setOf("confusion"))
        val result = holder(endTurn(actor), actor)
        assertEquals(1.0, result.hpFraction, 1e-9)
        assertNull(result.statusId)
        assertFalse("confusion" in result.canonicalKnownVolatileEffectIds)
        assertEquals("", result.knownHeldItemId)
    }

    @Test
    fun `inactive Lum holders do not eat their berry during residual projection`() {
        val actor = mon(BattleSide.ALLY, item = "lumberry").copyState(activeSlot = null, statusId = "psn")
        val result = holder(endTurn(actor), actor)
        assertEquals("psn", result.statusId)
        assertEquals("lumberry", result.knownHeldItemId)
        assertEquals(1.0, result.hpFraction, 1e-9)
    }

    @Test
    fun `Lum status cure clears old confusion before the same turn target action`() {
        val actor = mon(BattleSide.ALLY, speed = 200)
        val target = mon(BattleSide.OPPONENT, item = "lumberry", speed = 50)
            .copyState(knownVolatileEffectIds = setOf("confusion"))
        val history = RecursiveActionHistory(confusedPokemonIds = setOf(target.battlePokemonId))
        // The pending Lum Update consumes the berry before the new status move; poison does not add
        // a legitimate full-paralysis branch that would obscure the old-confusion regression.
        val outcomes = effectTurn(fixture.state(actor, target), move("poisonpowder", "psn", BattleMoveEffectKind.STATUS),
            reply = replyAttack(), history = history)
        assertTrue(outcomes.all { holder(it.stateBeforeResidual, target).hpFraction == 1.0 }, "No remaining old-confusion self-hit branch")
        assertTrue(outcomes.all { holder(it.stateBeforeResidual, actor).hpFraction < 1.0 }, "The cured target's attack must execute")
    }

    @Test
    fun `Lum status cure clears old confusion from the next recursive history`() {
        val actor = mon(BattleSide.ALLY, speed = 200)
        val target = mon(BattleSide.OPPONENT, item = "lumberry", speed = 50)
            .copyState(knownVolatileEffectIds = setOf("confusion"))
        val state = fixture.state(actor, target)
        val history = RecursiveActionHistory(confusedPokemonIds = setOf(target.battlePokemonId))
        val action = move("thunderwave", "par", BattleMoveEffectKind.STATUS)
        val reply = foeWait()
        effectTurn(state, action, reply, history).forEach { outcome ->
            assertFalse(target.battlePokemonId in RecursiveHistoryProjector.project(history, state, outcome, action, reply).confusedPokemonIds)
        }
    }

    private fun mon(side: BattleSide, ability: String? = null, item: String? = null, speed: Int = 100) =
        fixture.mon(side, 0, ability = ability, speed = speed).copyState(knownHeldItemId = item)

    private fun holder(state: BattleStateView, pokemon: BattlePokemonStateView) = state.pokemon.single { it.battlePokemonId == pokemon.battlePokemonId }
    private fun endTurn(pokemon: BattlePokemonStateView) = LocalEndTurnStateProjector.project(fixture.state(pokemon))
    private fun contact(actor: BattlePokemonStateView, target: BattlePokemonStateView,
        state: BattleStateView = fixture.state(actor, target)): List<LocalContactAfterHitBranch> =
        LocalContactAfterHitMechanics.project(state, actor.battlePokemonId, target.battlePokemonId,
            fixture.attack(effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(),
                false, mechanicFlags = setOf("contact"))), 0.1)

    private fun move(id: String, value: String, kind: BattleMoveEffectKind = BattleMoveEffectKind.VOLATILE_STATUS,
        target: BattleMoveEffectTarget = BattleMoveEffectTarget.SELECTED_TARGET) = BattleActionCandidate(
        "$id:0", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(kind, target, 1.0, valueId = value)), false)))

    private fun foeWait() = BattleActionCandidate("opponent-wait", BattleActionKind.WAIT)
    private fun replyAttack() = BattleActionCandidate("opponent-tackle", BattleActionKind.USE_MOVE,
        actorSlot = 0, moveSlot = 0, moveId = "cobblemon:tackle", targets = listOf(BattleTargetSlot(BattleSide.ALLY, 0)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 10,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false)))

    private fun effectTurn(state: BattleStateView, action: BattleActionCandidate, reply: BattleActionCandidate = foeWait(),
        history: RecursiveActionHistory = RecursiveActionHistory()): List<PublicTurnProjection> {
        val context = PublicBattleTacticalCalculator.calculate(fixture.context(state, action))
        return PublicSingleTurnProjector.project(state, context.candidates.single(), reply, context, history = history)
    }

    private fun terrain(state: BattleStateView, id: String, turns: Int = 3) = state.derive(field = BattleFieldStateView(
        state.field.weather, BattleTimedEffectView(id, turns), state.field.roomEffects, state.field.globalEffects, state.field.sideConditions))
    private fun weather(state: BattleStateView, id: String, turns: Int) = state.derive(field = BattleFieldStateView(
        BattleTimedEffectView(id, turns), state.field.terrain, state.field.roomEffects, state.field.globalEffects, state.field.sideConditions))
    private fun safeguard(state: BattleStateView, side: BattleSide) = state.derive(field = BattleFieldStateView(
        state.field.weather, state.field.terrain, state.field.roomEffects, state.field.globalEffects,
        BattleSide.entries.associateWith { if (it == side) listOf(BattleTimedEffectView("safeguard", 3)) else emptyList() }))
    private fun magicRoom(state: BattleStateView) = state.derive(field = BattleFieldStateView(
        state.field.weather, state.field.terrain, listOf(BattleTimedEffectView("magicroom", 3)), state.field.globalEffects, state.field.sideConditions))
}
