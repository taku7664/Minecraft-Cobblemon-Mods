package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleWorldHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleWorldPosteriorIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleWorldPosteriorUpdater
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBuildKnowledge
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonBuildHypothesis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeBattleWorldPosteriorUpdaterTest {
    @Test
    fun `confirmed public Tera keeps matching worlds and renormalizes their priors`() {
        val result = NativeBattleWorldPosteriorUpdater.update(
            worlds = listOf(
                world("fire", 0.6, "fire"),
                world("water-a", 0.3, "water"),
                world("water-b", 0.1, "cobblemon:water"),
            ),
            state = state(listOf(teraInference("Water"))),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(listOf("water-a", "water-b"), result.worlds.map { it.hypothesisId })
        assertEquals(0.75, result.worlds[0].probability, 1e-12)
        assertEquals(0.25, result.worlds[1].probability, 1e-12)
        assertEquals(1.0, result.worlds.sumOf { it.probability }, 1e-12)
    }

    @Test
    fun `persistent confirmed inference still updates worlds after its event left recent history`() {
        val publicInferenceWithNoRetainedEvent = BattleInferenceView(
            subjectPokemonId = OPPONENT,
            categoryId = "tera_type",
            candidateId = "water",
            confidence = BattleInferenceConfidence.CONFIRMED,
            basis = setOf(BattleInferenceBasis.PUBLIC_REVEAL),
            evidenceEventSequences = emptyList(),
        )

        val result = NativeBattleWorldPosteriorUpdater.update(
            listOf(world("fire", 0.5, "fire"), world("water", 0.5, "water")),
            state(listOf(publicInferenceWithNoRetainedEvent)),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(listOf("water"), result.worlds.map { it.hypothesisId })
        assertEquals(1.0, result.worlds.single().probability, 1e-12)
    }

    @Test
    fun `unconfirmed or non-public Tera candidates cannot delete native worlds`() {
        val untouched = listOf(world("fire", 0.6, "fire"), world("water", 0.4, "water"))
        val result = NativeBattleWorldPosteriorUpdater.update(
            worlds = untouched,
            state = state(listOf(
                BattleInferenceView(
                    OPPONENT,
                    "tera_type",
                    "water",
                    BattleInferenceConfidence.LIKELY,
                    basis = setOf(BattleInferenceBasis.PUBLIC_REVEAL),
                ),
                BattleInferenceView(
                    OPPONENT,
                    "tera_type",
                    "water",
                    BattleInferenceConfidence.CONFIRMED,
                    basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                ),
            )),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(untouched, result.worlds)
    }

    @Test
    fun `conflicting confirmed public Tera evidence fails closed`() {
        val result = NativeBattleWorldPosteriorUpdater.update(
            worlds = listOf(world("fire", 0.5, "fire"), world("water", 0.5, "water")),
            state = state(listOf(teraInference("fire"), teraInference("water"))),
        )

        assertTrue(result.worlds.isEmpty())
        assertEquals(
            listOf(NativeBattleWorldPosteriorIssueCode.CONFLICTING_CONFIRMED_TERA),
            result.issues.map { it.code },
        )
        assertEquals(OPPONENT, result.issues.single().battlePokemonId)
    }

    @Test
    fun `a reveal with no matching prior world fails instead of reviving an arbitrary build`() {
        val result = NativeBattleWorldPosteriorUpdater.update(
            worlds = listOf(world("fire", 0.6, "fire"), world("water", 0.4, "water")),
            state = state(listOf(teraInference("grass"))),
        )

        assertTrue(result.worlds.isEmpty())
        assertEquals(
            listOf(NativeBattleWorldPosteriorIssueCode.NO_WORLD_MATCHES_CONFIRMED_TERA),
            result.issues.map { it.code },
        )
        assertEquals(OPPONENT, result.issues.single().battlePokemonId)
    }

    @Test
    fun `a stale world roster fails before public evidence can silently remove it`() {
        val stale = NativeBattleWorldHypothesis(
            hypothesisId = "stale",
            probability = 1.0,
            pokemon = listOf(build(ALLY, NativeBuildKnowledge.EXACT_OWN, "electric")),
        )

        val result = NativeBattleWorldPosteriorUpdater.update(
            worlds = listOf(stale),
            state = state(listOf(teraInference("water"))),
        )

        assertTrue(result.worlds.isEmpty())
        assertEquals(
            listOf(NativeBattleWorldPosteriorIssueCode.WORLD_ROSTER_MISMATCH),
            result.issues.map { it.code },
        )
    }

    @Test
    fun `duplicate ids and an unnormalized prior fail explicitly`() {
        val duplicateIds = NativeBattleWorldPosteriorUpdater.update(
            worlds = listOf(world("same", 0.5, "fire"), world("same", 0.5, "water")),
            state = state(),
        )
        assertEquals(
            listOf(NativeBattleWorldPosteriorIssueCode.DUPLICATE_WORLD_ID),
            duplicateIds.issues.map { it.code },
        )

        val unnormalized = NativeBattleWorldPosteriorUpdater.update(
            worlds = listOf(world("fire", 0.6, "fire"), world("water", 0.3, "water")),
            state = state(),
        )
        assertEquals(
            listOf(NativeBattleWorldPosteriorIssueCode.PRIOR_NOT_NORMALIZED),
            unnormalized.issues.map { it.code },
        )
    }

    private fun teraInference(type: String) = BattleInferenceView(
        subjectPokemonId = OPPONENT,
        categoryId = "tera_type",
        candidateId = type,
        confidence = BattleInferenceConfidence.CONFIRMED,
        basis = setOf(BattleInferenceBasis.PUBLIC_REVEAL),
    )

    private fun state(inferences: List<BattleInferenceView> = emptyList()) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 12,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "cobblemon:pikachu"),
            pokemon(OPPONENT, BattleSide.OPPONENT, "cobblemon:fluttermane"),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = inferences,
    )

    private fun pokemon(id: UUID, side: BattleSide, species: String) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = species,
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
    )

    private fun world(id: String, probability: Double, opponentTera: String) = NativeBattleWorldHypothesis(
        hypothesisId = id,
        probability = probability,
        pokemon = listOf(
            build(ALLY, NativeBuildKnowledge.EXACT_OWN, "electric"),
            build(OPPONENT, NativeBuildKnowledge.PUBLIC_HYPOTHESIS, opponentTera),
        ),
    )

    private fun build(id: UUID, knowledge: NativeBuildKnowledge, tera: String) = NativePokemonBuildHypothesis(
        battlePokemonId = id,
        knowledge = knowledge,
        abilityId = if (id == ALLY) "static" else "protosynthesis",
        itemId = if (id == ALLY) "lightball" else "choicespecs",
        nature = "Timid",
        gender = "N",
        evs = EVS,
        ivs = IVS,
        teraTypeId = tera,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000100")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val EVS: Map<String, Int> = mapOf(
            "hp" to 0, "atk" to 0, "def" to 0, "spa" to 252, "spd" to 4, "spe" to 252,
        )
        val IVS: Map<String, Int> = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 31 }
    }
}
