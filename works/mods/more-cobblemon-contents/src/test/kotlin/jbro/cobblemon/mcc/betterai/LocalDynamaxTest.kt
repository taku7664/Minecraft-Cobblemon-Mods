package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalPersistentMoveState
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicPersistentMoveState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Dynamax in the legacy calculation: Max Moves' effects read from their `onHit` code, the doubled HP of a Dynamaxed
 * Pokemon and its three turns, and the named callback table kept to moves no shape reads.
 */
class LocalDynamaxTest {
    @Test
    fun `Max Moves carry their side effects read from onHit`() {
        assertTrue(effects("maxairstream").any { it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER &&
            it.statStages == mapOf("speed" to 1) }, effects("maxairstream").toString())
        assertTrue(effects("maxstrike").any { it.kind == BattleMoveEffectKind.STAT_STAGE &&
            it.target == BattleMoveEffectTarget.SELECTED_TARGET && it.statStages == mapOf("speed" to -1) })
        assertTrue(effects("maxflare").any { it.kind == BattleMoveEffectKind.WEATHER && it.valueId == "sunnyday" })
        assertTrue(effects("maxlightning").any { it.kind == BattleMoveEffectKind.TERRAIN && it.valueId == "electricterrain" })
        assertTrue(effects("gmaxsteelsurge").any { it.kind == BattleMoveEffectKind.SIDE_CONDITION &&
            it.target == BattleMoveEffectTarget.TARGET_SIDE && it.valueId == "gmaxsteelsurge" })
        // Every Max Move with an onHit is read.
        val readable = BattleDeclarativeMoveEffects.shapedHandlersReadable(SOURCE)
        val maxMoves = Regex("""\n  (max[a-z]+): \{""").findAll(SOURCE).map { it.groupValues[1] }.filter { it in readable }.toList()
        assertEquals(19, maxMoves.size, maxMoves.toString())
        assertTrue(maxMoves.all { readable.getValue(it) }, maxMoves.filterNot { readable.getValue(it) }.toString())
        // G-Max Moves: all but the ones whose onHit is logic (a move's PP, a Berry, a list of screens, a team cure, a
        // condition on the target, a sleep that checks for a status).
        val gmax = readable.filterKeys { it.startsWith("gmax") }
        assertEquals(setOf("gmaxdepletion", "gmaxreplenish", "gmaxwindrage", "gmaxsweetness", "gmaxmeltdown", "gmaxsnooze"),
            gmax.filterValues { !it }.keys)
        assertTrue(effects("gmaxsandblast").any { it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.valueId == "partiallytrapped" })
    }

    @Test
    fun `moves taken off the named table keep their effects through the shapes`() {
        assertEquals(0.5, effects("filletaway").single { it.kind == BattleMoveEffectKind.MAX_HP_RECOIL }.fractionRange!!.minimum, 1e-9)
        val dire = effects("direclaw").filter { it.kind == BattleMoveEffectKind.STATUS }
        assertEquals(setOf("psn", "par", "slp"), dire.mapTo(hashSetOf()) { it.valueId })
        assertTrue(dire.all { kotlin.math.abs(it.probability!! - 0.5 / 3.0) < 1e-9 }, dire.toString())
        val tri = effects("triattack").filter { it.kind == BattleMoveEffectKind.STATUS }
        assertEquals(setOf("brn", "par", "frz"), tri.mapTo(hashSetOf()) { it.valueId })
        // Protect's stall counter stays a flag, not a volatile on the user.
        assertTrue(effects("protect").none { it.valueId == "stall" }, effects("protect").toString())
    }

    @Test
    fun `the named callback table holds only moves no shape reads`() {
        val readable = BattleDeclarativeMoveEffects.shapedHandlersReadable(SOURCE)
        // Curse's Ghost half (an HP cost in onHit) reads as a shape; its other half is set in onTryHit, which no shape reads.
        val readableOnHitOnly = setOf("curse")
        val wronglyListed = BattleDeclarativeMoveEffects.CALLBACK_EFFECT_MOVES.filter { readable[it] == true && it !in readableOnHitOnly }
        assertTrue(wronglyListed.isEmpty(), "read as shapes, so off the table: $wronglyListed")
        assertEquals(11, BattleDeclarativeMoveEffects.CALLBACK_EFFECT_MOVES.size, BattleDeclarativeMoveEffects.CALLBACK_EFFECT_MOVES.toString())
    }

    @Test
    fun `a Dynamaxed target takes half the share of a hit and of a set amount`() {
        val user = mon(BattleSide.ALLY)
        val plain = mon(BattleSide.OPPONENT)
        val dynamaxed = LocalDynamax.start(plain)
        val hit = attack("tackle", 80.0)
        val normal = rolls(hit, user, plain)
        val halved = rolls(hit, user, dynamaxed)
        assertEquals(normal.average() / 2.0, halved.average(), 1e-9)
        val seismic = attack("seismictoss", 1.0, BattleMoveEffectView(BattleMoveEffectKind.FIXED_DAMAGE_LEVEL, BattleMoveEffectTarget.SELECTED_TARGET, 1.0))
        assertEquals(rolls(seismic, user, plain).average() / 2.0, rolls(seismic, user, dynamaxed).average(), 1e-9)
    }

    @Test
    fun `Dynamax lasts three turns, the one it starts on included`() {
        val started = Cobblemon173PublicPersistentMoveState.afterVolatile(emptySet(), "dynamax", true, null, null)
        assertEquals(setOf("dynamax", "dynamaxturns:3"), started)
        assertEquals(setOf("dynamax", "dynamaxturns:2"), Cobblemon173PublicPersistentMoveState.advance(started, 1))
        assertFalse(LocalDynamax.active(mon(BattleSide.OPPONENT).copyState(
            knownVolatileEffectIds = Cobblemon173PublicPersistentMoveState.advance(started, 3))))
        assertEquals(emptySet<String>(), Cobblemon173PublicPersistentMoveState.afterVolatile(started, "dynamax", false, null, null))

        // In projection, the turn-end step counts it down and ends it.
        var state = state(mon(BattleSide.ALLY), LocalDynamax.start(mon(BattleSide.OPPONENT)))
        repeat(2) { state = LocalPersistentMoveState.afterResidual(state) }
        assertTrue(LocalDynamax.active(state.pokemon[1]))
        state = LocalPersistentMoveState.afterResidual(state)
        assertFalse(LocalDynamax.active(state.pokemon[1]), state.pokemon[1].knownVolatileEffectIds.toString())
        assertNotEquals(null, state.pokemon[1])
    }

    private fun rolls(candidate: BattleActionCandidate, user: BattlePokemonStateView, foe: BattlePokemonStateView): List<Double> {
        val context = context(state(user, foe), candidate)
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        return requireNotNull(PublicBattleTacticalCalculator.conservativeDamageRollFractions(calculated.candidates.single(), calculated, BattleSide.ALLY))
    }

    private fun attack(id: String, power: Double, vararg effects: BattleMoveEffectView) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = power, accuracy = 100.0,
            priority = 0, currentPp = 10, targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects.toList(), scriptedBehavior = false),
        ),
    )

    private fun effects(id: String): List<BattleMoveEffectView> = requireNotNull(MOVES[id]) { "no $id" }.effects

    private fun context(state: BattleStateView, candidate: BattleActionCandidate) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state, candidates = listOf(candidate),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(user: BattlePokemonStateView, foe: BattlePokemonStateView) = BattleStateView(
        battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3, pokemon = listOf(user, foe),
        field = BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0, speciesId = "showdown:probe", formId = null,
        level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("water"),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(300, 300), attack = BattleIntegerRange(150, 150),
            defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(100, 100),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
        knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf("water"),
    )

    private companion object {
        val SOURCE: String by lazy {
            ZipInputStream(requireNotNull(LocalDynamaxTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val MOVES: Map<String, BattleMoveEffectsView> by lazy { BattleDeclarativeMoveEffects.parse(SOURCE) }
    }
}
