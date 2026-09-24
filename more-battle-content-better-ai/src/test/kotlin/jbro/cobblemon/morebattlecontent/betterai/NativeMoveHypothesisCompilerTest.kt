package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveCandidatePoolView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveHypothesisCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeMoveHypothesisCompilerTest {
    @Test
    fun `guess slots remain unresolved and never become native moves`() {
        val result = NativeMoveHypothesisCompiler.compile(
            opponent,
            catalog(
                expected(0, "cobblemon:moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
                guess(1, BattleOpponentMoveGroup.STATUS_OTHER),
                confirmed(2, "shadowball", BattleOpponentMoveGroup.COVERAGE_ATTACK),
                guess(3, BattleOpponentMoveGroup.PURE_SETUP),
            ),
        )

        assertEquals(listOf("moonblast", "shadowball"), result.nativeMoveIds)
        assertEquals(listOf(1, 3), result.unresolvedSlots.map { it.slot })
        assertEquals(
            listOf(BattleOpponentMoveGroup.STATUS_OTHER, BattleOpponentMoveGroup.PURE_SETUP),
            result.unresolvedSlots.map { it.group },
        )
        assertTrue(result.hasUnresolvedSlots)
        assertTrue(result.isCompleteSet)
        assertEquals(listOf(0, 1, 2, 3), result.slots.map { it.slot })
        assertEquals(
            listOf(
                BattleOpponentMoveKnowledge.EXPECTED,
                BattleOpponentMoveKnowledge.GUESS,
                BattleOpponentMoveKnowledge.CONFIRMED,
                BattleOpponentMoveKnowledge.GUESS,
            ),
            result.slots.map { it.knowledge },
        )
    }

    @Test
    fun `zero or one stab slot is preserved without inventing a second dual type stab`() {
        val noStab = NativeMoveHypothesisCompiler.compile(
            opponent,
            catalog(
                expected(0, "powergem", BattleOpponentMoveGroup.COVERAGE_ATTACK),
                expected(1, "mysticalfire", BattleOpponentMoveGroup.COVERAGE_ATTACK),
                guess(2, BattleOpponentMoveGroup.STATUS_OTHER),
                guess(3, BattleOpponentMoveGroup.OTHER),
            ),
        )
        val oneStab = NativeMoveHypothesisCompiler.compile(
            opponent,
            catalog(
                confirmed(0, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
                expected(1, "powergem", BattleOpponentMoveGroup.COVERAGE_ATTACK),
                guess(2, BattleOpponentMoveGroup.STATUS_OTHER),
                guess(3, BattleOpponentMoveGroup.OTHER),
            ),
        )

        assertEquals(0, noStab.concreteMoves.count { it.group == BattleOpponentMoveGroup.STAB_ATTACK })
        assertEquals(listOf("powergem", "mysticalfire"), noStab.nativeMoveIds)
        assertEquals(1, oneStab.concreteMoves.count { it.group == BattleOpponentMoveGroup.STAB_ATTACK })
        assertEquals(listOf("moonblast", "powergem"), oneStab.nativeMoveIds)
    }

    @Test
    fun `normalized slots alone own native move input`() {
        val slots = listOf(
            confirmed(0, "Moon-Blast", BattleOpponentMoveGroup.STAB_ATTACK),
            expected(1, "cobblemon:Power Gem", BattleOpponentMoveGroup.COVERAGE_ATTACK),
            guess(2, BattleOpponentMoveGroup.STATUS_OTHER),
            guess(3, BattleOpponentMoveGroup.OTHER),
        )
        val first = NativeMoveHypothesisCompiler.compile(opponent, catalog(*slots.toTypedArray()))
        val unrelatedPool = BattlePublicMoveCandidatePoolView(
            opponent.battlePokemonId,
            opponent.speciesId,
            opponent.formId,
            setOf("thunderbolt", "psychic", "energyball"),
            "different-hidden-or-usage-pool",
            mapOf(
                "thunderbolt" to details,
                "psychic" to details,
                "energyball" to details,
            ),
        )
        val second = NativeMoveHypothesisCompiler.compile(
            opponent,
            BattlePublicActionCatalogView(
                entries = emptyList(),
                candidatePools = listOf(unrelatedPool),
                opponentMoveInferences = listOf(BattleOpponentMoveInferenceView(opponent.battlePokemonId, slots)),
            ),
        )

        assertEquals(first, second)
        assertEquals(listOf("moonblast", "powergem"), first.nativeMoveIds)
    }

    @Test
    fun `missing normalized inference cannot fall back to a broad candidate pool`() {
        val pool = BattlePublicMoveCandidatePoolView(
            opponent.battlePokemonId,
            opponent.speciesId,
            opponent.formId,
            setOf("moonblast"),
            "broad-public-pool",
            mapOf("moonblast" to details),
        )

        val result = NativeMoveHypothesisCompiler.compile(
            opponent,
            BattlePublicActionCatalogView(emptyList(), candidatePools = listOf(pool)),
        )

        assertTrue(result.concreteMoves.isEmpty())
        assertTrue(result.unresolvedSlots.isEmpty())
        assertFalse(result.hasExecutableMove)
        assertEquals("normalized_inference_missing", result.unavailableReason)
    }

    @Test
    fun `a partial logical slot shape is unavailable even when it has an executable move`() {
        val result = NativeMoveHypothesisCompiler.compile(
            opponent,
            catalog(
                expected(0, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
                guess(1, BattleOpponentMoveGroup.STATUS_OTHER),
                guess(2, BattleOpponentMoveGroup.OTHER),
            ),
        )

        assertTrue(result.hasExecutableMove)
        assertFalse(result.isCompleteSet)
        assertEquals("normalized_inference_incomplete", result.unavailableReason)
    }

    private fun catalog(vararg slots: BattleOpponentMoveSlotView) = BattlePublicActionCatalogView(
        entries = emptyList(),
        opponentMoveInferences = listOf(BattleOpponentMoveInferenceView(opponent.battlePokemonId, slots.toList())),
    )

    private fun expected(slot: Int, move: String, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot,
        move,
        group,
        BattleOpponentMoveKnowledge.EXPECTED,
        BattleOpponentMoveSource.LEARNSET_EXPECTATION,
        details,
    )

    private fun confirmed(slot: Int, move: String, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot,
        move,
        group,
        BattleOpponentMoveKnowledge.CONFIRMED,
        BattleOpponentMoveSource.PUBLIC_REVEAL,
        details,
    )

    private fun guess(slot: Int, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot,
        null,
        group,
        BattleOpponentMoveKnowledge.GUESS,
        BattleOpponentMoveSource.GROUP_GUESS,
    )

    private val details = BattleMoveCandidateView(
        "fairy",
        BattleMoveDamageCategory.SPECIAL,
        80.0,
        100.0,
        0,
        16,
    )
    private val opponent = BattlePokemonStateView(
        UUID.fromString("00000000-0000-0000-0000-000000000222"),
        BattleSide.OPPONENT,
        0,
        "showdown:fluttermane",
        null,
        50,
        1.0,
        null,
        emptyMap(),
        setOf("moonblast"),
        null,
        null,
        false,
        setOf("ghost", "fairy"),
    )
}
