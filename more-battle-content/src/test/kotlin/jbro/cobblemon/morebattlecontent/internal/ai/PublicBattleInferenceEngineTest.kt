package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicBattleInferenceEngineTest {
    @Test
    fun `unknown opponent ability exposes only public species possibilities`() {
        val opponent = pokemon(BattleSide.OPPONENT, knownAbilityId = null)
        val ally = pokemon(BattleSide.ALLY, knownAbilityId = "clearbody")
        val requested = mutableListOf<Pair<String, String?>>()
        val knowledge = PublicSpeciesInferenceKnowledge { speciesId, formId ->
            requested += speciesId to formId
            listOf(
                PublicAbilityPossibility("roughskin", BattleAbilityAvailability.REGULAR),
                PublicAbilityPossibility("sandveil", BattleAbilityAvailability.HIDDEN),
            )
        }

        val inferences = PublicBattleInferenceEngine.infer(listOf(ally, opponent), knowledge)

        assertEquals(listOf(opponent.speciesId to opponent.formId), requested)
        assertEquals(listOf("roughskin", "sandveil"), inferences.map { it.candidateId })
        assertEquals(
            listOf(BattleAbilityAvailability.REGULAR, BattleAbilityAvailability.HIDDEN),
            inferences.map { it.abilityAvailability },
        )
        assertTrue(inferences.all { it.subjectPokemonId == opponent.battlePokemonId })
        assertTrue(inferences.all { it.categoryId == "ability" })
        assertTrue(inferences.all { it.confidence == BattleInferenceConfidence.POSSIBLE })
        assertTrue(inferences.all { it.basis == setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES) })
    }

    @Test
    fun `revealed ability suppresses hypotheses and unavailable species knowledge stays empty`() {
        val revealed = pokemon(BattleSide.OPPONENT, knownAbilityId = "roughskin")
        val unknown = pokemon(BattleSide.OPPONENT, knownAbilityId = null)

        assertTrue(
            PublicBattleInferenceEngine.infer(listOf(revealed)) { _, _ ->
                listOf(PublicAbilityPossibility("sandveil", BattleAbilityAvailability.HIDDEN))
            }.isEmpty(),
        )
        assertTrue(PublicBattleInferenceEngine.infer(listOf(unknown)) { _, _ -> null }.isEmpty())
    }

    @Test
    fun `same base priority action order is retained as an observed relation not a speed claim`() {
        val allyId = UUID.randomUUID()
        val opponentId = UUID.randomUUID()
        val ally = pokemon(BattleSide.ALLY, null, allyId)
        val opponent = pokemon(BattleSide.OPPONENT, null, opponentId)
        val events = listOf(
            BattleObservedEventView(
                sequence = 10,
                turn = 3,
                kind = BattleObservedEventKind.ACTION_ORDER,
                actorPokemonId = opponentId,
                publicValueId = "tackle",
                baseMovePriority = 0,
            ),
            BattleObservedEventView(
                sequence = 12,
                turn = 3,
                kind = BattleObservedEventKind.ACTION_ORDER,
                actorPokemonId = allyId,
                publicValueId = "protect",
                baseMovePriority = 0,
            ),
        )

        val inferences = PublicBattleInferenceEngine.infer(
            pokemon = listOf(ally, opponent),
            speciesKnowledge = { _, _ -> emptyList() },
            observedEvents = events,
        )

        assertEquals(1, inferences.size)
        val relation = inferences.single()
        assertEquals(opponentId, relation.subjectPokemonId)
        assertEquals(allyId, relation.relatedPokemonId)
        assertEquals("observed_action_order", relation.categoryId)
        assertEquals("BEFORE_AT_SAME_BASE_PRIORITY", relation.candidateId)
        assertEquals(BattleInferenceConfidence.CONFIRMED, relation.confidence)
        assertEquals(setOf(BattleInferenceBasis.ACTION_ORDER), relation.basis)
        assertEquals(listOf(10L, 12L), relation.evidenceEventSequences)
        assertTrue(inferences.none { it.categoryId == "speed" || it.categoryId == "held_item" })
    }

    @Test
    fun `different priority or repeated action actor does not create an order relation`() {
        val allyId = UUID.randomUUID()
        val opponentId = UUID.randomUUID()
        val pokemon = listOf(
            pokemon(BattleSide.ALLY, null, allyId),
            pokemon(BattleSide.OPPONENT, null, opponentId),
        )
        fun event(sequence: Long, actor: UUID, priority: Int) = BattleObservedEventView(
            sequence = sequence,
            turn = 4,
            kind = BattleObservedEventKind.ACTION_ORDER,
            actorPokemonId = actor,
            publicValueId = "move$sequence",
            baseMovePriority = priority,
        )

        val differentPriority = PublicBattleInferenceEngine.infer(
            pokemon,
            { _, _ -> emptyList() },
            listOf(event(1, opponentId, 1), event(2, allyId, 0)),
        )
        val repeatedActor = PublicBattleInferenceEngine.infer(
            pokemon,
            { _, _ -> emptyList() },
            listOf(event(1, opponentId, 0), event(2, opponentId, 0), event(3, allyId, 0)),
        )

        assertTrue(differentPriority.isEmpty())
        assertTrue(repeatedActor.isEmpty())
    }

    private fun pokemon(side: BattleSide, knownAbilityId: String?, id: UUID = UUID.randomUUID()) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = if (side == BattleSide.OPPONENT) "showdown:garchomp" else "cobblemon:metagross",
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = knownAbilityId,
        knownHeldItemId = null,
        fainted = false,
    )
}
