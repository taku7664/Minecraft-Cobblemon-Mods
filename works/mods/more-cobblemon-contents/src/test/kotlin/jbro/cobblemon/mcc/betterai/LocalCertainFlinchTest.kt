package jbro.cobblemon.mcc.betterai

import java.util.zip.ZipInputStream
import jbro.cobblemon.mcc.betterai.evaluation.LocalRootFlinchValue
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A certain flinch frees the turn only when the one condition it can fail on is declared (G-921): Fake Out's first turn
 * out. Upper Hand flinches as surely, but only into a priority move its data does not describe.
 */
class LocalCertainFlinchTest {
    private val effects: Map<String, BattleMoveEffectsView> by lazy {
        ZipInputStream(requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Missing data/moves.js")
                if (entry.name == "data/moves.js") return@lazy BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
            @Suppress("UNREACHABLE_CODE")
            emptyMap()
        }
    }

    @Test
    fun `only a certain flinch with a declared condition frees the turn`() {
        val certain = listOf("fakeout", "upperhand", "airslash", "ironhead", "tackle")
            .filter { LocalRootFlinchValue.certain(move(it)) }
        assertEquals(listOf("fakeout"), certain)
    }

    @Test
    fun `upper hand waits for an attack with priority, sucker punch for any attack`() {
        fun pending(id: String) = effects.getValue(id).requirements
            .single { it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE }.acceptedValueIds
        assertEquals(setOf(BattleDeclarativeMoveEffects.PENDING_PRIORITY), pending("upperhand"))
        assertEquals(emptySet<String>(), pending("suckerpunch"))
    }

    private fun move(id: String) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 40.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = effects.getValue(id),
        ),
    )
}
