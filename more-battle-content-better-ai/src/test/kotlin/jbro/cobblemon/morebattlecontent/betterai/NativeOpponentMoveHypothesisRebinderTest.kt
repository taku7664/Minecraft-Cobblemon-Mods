package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentMoveHypothesisRebinder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeOpponentMoveHypothesisRebinderTest {
    @Test
    fun `a public reveal fills its former group slot in the compact native set`() {
        val previous = inference(
            expected(0, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
            expected(1, "powergem", BattleOpponentMoveGroup.COVERAGE_ATTACK),
            guessed(2, BattleOpponentMoveGroup.STATUS_OTHER),
            guessed(3, BattleOpponentMoveGroup.OTHER),
        )
        val current = inference(
            expected(0, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
            expected(1, "powergem", BattleOpponentMoveGroup.COVERAGE_ATTACK),
            revealed(2, "thunderwave", BattleOpponentMoveGroup.STATUS_OTHER),
            guessed(3, BattleOpponentMoveGroup.OTHER),
        )

        val plan = NativeOpponentMoveHypothesisRebinder.plan(
            definition = definition(listOf("moonblast", "powergem")),
            previousCatalog = catalog(previous),
            currentCatalog = catalog(current),
            currentPublicPokemonIds = setOf(OPPONENT),
            revealedMoveIdsByPokemon = mapOf(OPPONENT to setOf("cobblemon:thunder_wave")),
        )

        assertEquals(listOf("moonblast", "powergem", "thunderwave"), plan.definition.p2Team.single().moves)
        assertEquals(1, plan.rebindings.size)
        assertEquals(listOf("moonblast", "powergem"), plan.rebindings.single().expectedMoveIds)
        assertEquals(
            listOf("moonblast", "powergem", "thunderwave"),
            plan.rebindings.single().replacementMoveIds,
        )
        assertEquals(
            "thunderwave",
            plan.catalog.inferredMovesForPokemon(OPPONENT)?.slots?.single { it.slot == 2 }?.moveId,
        )
    }

    @Test
    fun `pre replay planning does not apply unrelated type based expectation changes early`() {
        val previous = inference(
            expected(0, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
            expected(1, "powergem", BattleOpponentMoveGroup.COVERAGE_ATTACK),
            revealed(2, "thunderwave", BattleOpponentMoveGroup.STATUS_OTHER),
            guessed(3, BattleOpponentMoveGroup.OTHER),
        )
        val current = inference(
            expected(0, "powergem", BattleOpponentMoveGroup.STAB_ATTACK),
            expected(1, "moonblast", BattleOpponentMoveGroup.COVERAGE_ATTACK),
            revealed(2, "thunderwave", BattleOpponentMoveGroup.STATUS_OTHER),
            guessed(3, BattleOpponentMoveGroup.OTHER),
        )

        val beforeObservedTurn = NativeOpponentMoveHypothesisRebinder.plan(
            definition = definition(listOf("moonblast", "powergem", "thunderwave")),
            previousCatalog = catalog(previous),
            currentCatalog = catalog(current),
            currentPublicPokemonIds = setOf(OPPONENT),
            revealedMoveIdsByPokemon = emptyMap(),
        )
        val afterObservedTurn = NativeOpponentMoveHypothesisRebinder.plan(
            definition = beforeObservedTurn.definition,
            previousCatalog = beforeObservedTurn.catalog,
            currentCatalog = catalog(current),
            currentPublicPokemonIds = setOf(OPPONENT),
        )

        assertTrue(beforeObservedTurn.rebindings.isEmpty())
        assertEquals(listOf("moonblast", "powergem", "thunderwave"), beforeObservedTurn.definition.p2Team.single().moves)
        assertEquals(listOf("powergem", "moonblast", "thunderwave"), afterObservedTurn.definition.p2Team.single().moves)
        assertEquals(listOf("powergem", "moonblast", "thunderwave"),
            afterObservedTurn.rebindings.single().replacementMoveIds)
    }

    private fun catalog(inference: BattleOpponentMoveInferenceView) =
        BattlePublicActionCatalogView(emptyList(), opponentMoveInferences = listOf(inference))

    private fun inference(vararg slots: BattleOpponentMoveSlotView) =
        BattleOpponentMoveInferenceView(OPPONENT, slots.toList())

    private fun expected(
        slot: Int,
        moveId: String,
        group: BattleOpponentMoveGroup,
    ) = BattleOpponentMoveSlotView(
        slot,
        moveId,
        group,
        BattleOpponentMoveKnowledge.EXPECTED,
        BattleOpponentMoveSource.LEARNSET_EXPECTATION,
        details(moveId),
    )

    private fun revealed(
        slot: Int,
        moveId: String,
        group: BattleOpponentMoveGroup,
    ) = BattleOpponentMoveSlotView(
        slot,
        moveId,
        group,
        BattleOpponentMoveKnowledge.CONFIRMED,
        BattleOpponentMoveSource.PUBLIC_REVEAL,
        details(moveId),
    )

    private fun guessed(slot: Int, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot,
        null,
        group,
        BattleOpponentMoveKnowledge.GUESS,
        BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun details(moveId: String) = BattleMoveCandidateView(
        typeId = when (moveId) {
            "moonblast" -> "fairy"
            "powergem" -> "rock"
            else -> "electric"
        },
        damageCategory = if (moveId == "thunderwave") {
            BattleMoveDamageCategory.STATUS
        } else {
            BattleMoveDamageCategory.SPECIAL
        },
        power = if (moveId == "thunderwave") 0.0 else 80.0,
        accuracy = 100.0,
        priority = 0,
        currentPp = 10,
    )

    private fun definition(opponentMoves: List<String>) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(167, 173, 179, 181),
        p1Team = listOf(NativePokemonSet(
            "Ally",
            "Mew",
            listOf("tackle"),
            "synchronize",
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            "Opponent",
            "Flutter Mane",
            opponentMoves,
            "protosynthesis",
            uuid = OPPONENT.toString(),
        )),
    )

    private companion object {
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
    }
}
