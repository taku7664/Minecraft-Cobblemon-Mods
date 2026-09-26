package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleWorldHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBuildKnowledge
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialBattleDefinitionCompiler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveHypothesisCompiler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentMoveSetHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentRosterHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentRosterMaterializationIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentRosterStateMaterializer
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonBuildHypothesis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeOpponentRosterStateMaterializerTest {
    @Test
    fun `selected hidden bench becomes deterministic public-only synthetic states`() {
        val ally = pokemon(ALLY, BattleSide.ALLY, 0, "cobblemon:pikachu")
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val hypothesis = hypothesis(
            selected = listOf(0, 2, 4),
            assignments = mapOf(LEAD to 0),
        )

        val first = NativeOpponentRosterStateMaterializer.materialize(
            state(ally, lead),
            preview(),
            hypothesis,
            ::resolveSpecies,
        )
        val second = NativeOpponentRosterStateMaterializer.materialize(
            state(ally, lead),
            BattleOpponentTeamPreviewView(3, preview().pokemon.reversed()),
            hypothesis,
            ::resolveSpecies,
        )

        assertTrue(first.issues.isEmpty())
        assertTrue(second.issues.isEmpty())
        val roster = requireNotNull(first.roster)
        val repeated = requireNotNull(second.roster)
        assertEquals(listOf(ALLY, LEAD), roster.state.pokemon.take(2).map { it.battlePokemonId })
        assertEquals(3, roster.state.pokemon.count { it.side == BattleSide.OPPONENT })
        assertEquals(setOf("cobblemon:fluttermane", "cobblemon:rillaboom", "cobblemon:amoonguss"),
            roster.state.pokemon.filter { it.side == BattleSide.OPPONENT }.mapTo(linkedSetOf()) { it.speciesId })
        assertTrue(roster.state.pokemon.none { it.speciesId in setOf(
            "cobblemon:urshifu", "cobblemon:incineroar", "cobblemon:landorus",
        ) })
        assertEquals(lead.battlePokemonId, roster.state.pokemon.single { it.activeSlot == 0 && it.side == BattleSide.OPPONENT }.battlePokemonId)

        val synthetic = roster.state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot == null }
        assertEquals(2, synthetic.size)
        assertTrue(synthetic.all {
            it.hpFraction == 1.0 && it.statusId == null && it.statStages.isEmpty() &&
                it.knownMoveIds.isEmpty() && it.knownAbilityId == null &&
                it.knownHeldItemId == null && !it.fainted && it.knownVolatileEffectIds.isEmpty()
        })
        assertEquals(
            roster.opponentPreviewSlotByPokemonId,
            repeated.opponentPreviewSlotByPokemonId,
        )
        assertEquals(
            roster.state.pokemon.map { it.battlePokemonId },
            repeated.state.pokemon.map { it.battlePokemonId },
        )
        assertTrue(synthetic.none { it.battlePokemonId in setOf(ALLY, LEAD, PRIVATE_HIDDEN_ID) })
    }

    @Test
    fun `illusion keeps the displayed identity but simulates the assigned preview species`() {
        val disguisedLead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:garchomp")
        val hypothesis = hypothesis(
            selected = listOf(0, 1, 2),
            assignments = mapOf(LEAD to 1),
        )
        val preview = preview(
            "garchomp",
            "zoroark",
            "rotom",
            "mew",
            "ditto",
            "pikachu",
        )

        val result = NativeOpponentRosterStateMaterializer.materialize(
            state(disguisedLead),
            preview,
            hypothesis,
            ::resolveSpecies,
        )

        assertTrue(result.issues.isEmpty())
        val roster = requireNotNull(result.roster)
        val publicLead = roster.state.pokemon.single { it.battlePokemonId == LEAD }
        val nativeLeadIdentity = roster.identities.single { it.battlePokemonId == LEAD }
        assertEquals("cobblemon:garchomp", publicLead.speciesId)
        assertEquals("cobblemon:garchomp", nativeLeadIdentity.publicSpeciesId)
        assertEquals("zoroark", nativeLeadIdentity.showdownSpeciesId)
        assertEquals(1, roster.opponentPreviewSlotByPokemonId.getValue(LEAD))
        assertTrue(roster.state.pokemon.any {
            it.side == BattleSide.OPPONENT && it.activeSlot == null && it.speciesId == "cobblemon:garchomp"
        })
        assertEquals(
            "cobblemon:garchomp",
            roster.state.pokemon.last { it.side == BattleSide.OPPONENT }.speciesId,
        )
    }

    @Test
    fun `showdown form appearance is not misclassified as an Illusion`() {
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "showdown:zoroarkhisui")
        val preview = BattleOpponentTeamPreviewView(1, listOf(
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = 0,
                speciesId = "cobblemon:zoroark",
                formId = "Hisuian",
                level = 50,
                moveCandidatePool = null,
                buildCandidatePool = null,
                showdownSpeciesId = "Zoroark-Hisui",
            ),
        ))

        val result = NativeOpponentRosterStateMaterializer.materialize(
            state(lead, remainingOpponent = 1),
            preview,
            hypothesis(selected = listOf(0), assignments = mapOf(LEAD to 0)),
            { _, _ -> "zoroarkhisui" },
        )

        assertTrue(result.issues.isEmpty(), "issues=${result.issues}")
        assertEquals("zoroarkhisui", result.roster!!.identities.single().showdownSpeciesId)
    }

    @Test
    fun `double opening preserves both public leads and adds only two selected benches`() {
        val left = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val right = pokemon(RIGHT_LEAD, BattleSide.OPPONENT, 1, "cobblemon:urshifu")
        val hypothesis = hypothesis(
            selected = listOf(0, 1, 3, 5),
            assignments = mapOf(LEAD to 0, RIGHT_LEAD to 1),
        )

        val result = NativeOpponentRosterStateMaterializer.materialize(
            state(left, right, format = BattleFormat.DOUBLE, remainingOpponent = 4),
            BattleOpponentTeamPreviewView(4, preview().pokemon),
            hypothesis,
            ::resolveSpecies,
        )

        assertTrue(result.issues.isEmpty())
        val roster = requireNotNull(result.roster)
        assertEquals(mapOf(LEAD to 0, RIGHT_LEAD to 1),
            roster.opponentPreviewSlotByPokemonId.filterKeys { it in setOf(LEAD, RIGHT_LEAD) })
        assertEquals(listOf(0, 1), roster.state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .mapNotNull { it.activeSlot }.sorted())
        assertEquals(setOf(0, 1, 3, 5), roster.opponentPreviewSlotByPokemonId.values.toSet())
    }

    @Test
    fun `stale or malformed hypothesis fails without a partial roster`() {
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val missingReveal = NativeOpponentRosterStateMaterializer.materialize(
            state(lead),
            preview(),
            hypothesis(selected = listOf(0, 1, 2), assignments = emptyMap()),
            ::resolveSpecies,
        )
        val missingSlot = NativeOpponentRosterStateMaterializer.materialize(
            state(lead),
            preview(),
            hypothesis(selected = listOf(0, 1, 9), assignments = mapOf(LEAD to 0)),
            ::resolveSpecies,
        )
        val impossibleAppearance = NativeOpponentRosterStateMaterializer.materialize(
            state(pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:garchomp")),
            preview("garchomp", "zoroark", "rotom", "mew", "ditto", "pikachu"),
            hypothesis(selected = listOf(1, 2, 3), assignments = mapOf(LEAD to 1)),
            ::resolveSpecies,
        )

        assertNull(missingReveal.roster)
        assertTrue(missingReveal.identities.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterMaterializationIssueCode.REVEALED_ASSIGNMENT_MISMATCH),
            missingReveal.issues.mapTo(linkedSetOf()) { it.code },
        )
        assertNull(missingSlot.roster)
        assertTrue(missingSlot.identities.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterMaterializationIssueCode.SELECTED_PREVIEW_SLOT_MISSING),
            missingSlot.issues.mapTo(linkedSetOf()) { it.code },
        )
        assertNull(impossibleAppearance.roster)
        assertEquals(
            setOf(NativeOpponentRosterMaterializationIssueCode.PUBLIC_APPEARANCE_ORDER_UNAVAILABLE),
            impossibleAppearance.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `unresolvable public species fails closed and emits no synthetic state`() {
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val result = NativeOpponentRosterStateMaterializer.materialize(
            state(lead),
            preview(),
            hypothesis(selected = listOf(0, 2, 4), assignments = mapOf(LEAD to 0)),
        ) { species, form ->
            if (species == "cobblemon:amoonguss") null else resolveSpecies(species, form)
        }

        assertNull(result.roster)
        assertTrue(result.identities.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterMaterializationIssueCode.SHOWDOWN_SPECIES_UNAVAILABLE),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
        assertEquals(4, result.issues.single().previewSlotId)
    }

    @Test
    fun `action history cannot be erased to masquerade as a native opening`() {
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val move = BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = LEAD,
            publicValueId = "moonblast",
        )

        val result = NativeOpponentRosterStateMaterializer.materialize(
            state(lead, observedEvents = listOf(move)),
            preview(),
            hypothesis(selected = listOf(0, 2, 4), assignments = mapOf(LEAD to 0)),
            ::resolveSpecies,
        )

        assertNull(result.roster)
        assertEquals(
            setOf(NativeOpponentRosterMaterializationIssueCode.PUBLIC_STATE_NOT_INITIAL),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `synthetic identities are scoped by battle and never supplied by a private roster`() {
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val hypothesis = hypothesis(selected = listOf(0, 2, 4), assignments = mapOf(LEAD to 0))
        val first = requireNotNull(NativeOpponentRosterStateMaterializer.materialize(
            state(lead, battleId = BATTLE), preview(), hypothesis, ::resolveSpecies,
        ).roster)
        val repeated = requireNotNull(NativeOpponentRosterStateMaterializer.materialize(
            state(lead, battleId = BATTLE), preview(), hypothesis, ::resolveSpecies,
        ).roster)
        val otherBattle = requireNotNull(NativeOpponentRosterStateMaterializer.materialize(
            state(lead, battleId = OTHER_BATTLE), preview(), hypothesis, ::resolveSpecies,
        ).roster)

        val firstSynthetic = first.opponentPreviewSlotByPokemonId.filterValues { it == 2 }.keys.single()
        val repeatedSynthetic = repeated.opponentPreviewSlotByPokemonId.filterValues { it == 2 }.keys.single()
        val otherSynthetic = otherBattle.opponentPreviewSlotByPokemonId.filterValues { it == 2 }.keys.single()
        assertEquals(firstSynthetic, repeatedSynthetic)
        assertNotEquals(firstSynthetic, otherSynthetic)
        assertNotEquals(PRIVATE_HIDDEN_ID, firstSynthetic)
        assertNotNull(first.identities.singleOrNull { it.battlePokemonId == firstSynthetic })
    }

    @Test
    fun `materialized roster is a complete input to the native initial definition compiler`() {
        val ally = pokemon(
            ALLY,
            BattleSide.ALLY,
            0,
            "cobblemon:pikachu",
            combatStats = BattleCombatStatRangesView.exact(110, 75, 60, 70, 70, 110),
        )
        val lead = pokemon(LEAD, BattleSide.OPPONENT, 0, "cobblemon:fluttermane")
        val leadPresentation = BattleObservedEventView(
            sequence = 0,
            turn = 0,
            kind = BattleObservedEventKind.SWITCHED,
            actorPokemonId = LEAD,
        )
        val materialized = requireNotNull(NativeOpponentRosterStateMaterializer.materialize(
            state(ally, lead, observedEvents = listOf(leadPresentation)),
            preview(),
            hypothesis(selected = listOf(0, 2, 4), assignments = mapOf(LEAD to 0)),
            ::resolveSpecies,
        ).roster)
        assertEquals(listOf(leadPresentation), materialized.state.observedEvents)
        val opponentIds = materialized.state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .map(BattlePokemonStateView::battlePokemonId)
        val catalog = BattlePublicActionCatalogView(
            entries = listOf(BattlePokemonActionCatalogView(
                ALLY,
                listOf(BattlePublicMoveOptionView(
                    "thunderbolt",
                    MOVE_DETAILS,
                    BattlePublicMoveKnowledge.EXACT_OWN,
                )),
                moveSetComplete = true,
            )),
            opponentMoveInferences = opponentIds.map { id ->
                BattleOpponentMoveInferenceView(id, listOf(
                    BattleOpponentMoveSlotView(
                        slot = 0,
                        moveId = "tackle",
                        group = BattleOpponentMoveGroup.COVERAGE_ATTACK,
                        knowledge = BattleOpponentMoveKnowledge.EXPECTED,
                        source = BattleOpponentMoveSource.LEARNSET_EXPECTATION,
                        details = MOVE_DETAILS,
                    ),
                    guessed(1),
                    guessed(2),
                    guessed(3),
                ))
            },
        )
        val world = NativeBattleWorldHypothesis(
            hypothesisId = "complete-materialized-world",
            probability = 1.0,
            pokemon = materialized.state.pokemon.map { pokemon ->
                build(
                    pokemon.battlePokemonId,
                    if (pokemon.side == BattleSide.ALLY) {
                        NativeBuildKnowledge.EXACT_OWN
                    } else {
                        NativeBuildKnowledge.PUBLIC_HYPOTHESIS
                    },
                    if (pokemon.side == BattleSide.ALLY) "static" else "pressure",
                    if (pokemon.side == BattleSide.OPPONENT) {
                        requireNotNull(NativeMoveHypothesisCompiler.compile(pokemon, catalog).completeSetOrNull())
                    } else {
                        null
                    },
                )
            },
        )

        val result = NativeInitialBattleDefinitionCompiler.compile(
            materialized.state,
            catalog,
            materialized.identities,
            world,
            listOf(1, 2, 3, 4),
        )

        assertTrue(result.issues.isEmpty())
        val definition = requireNotNull(result.definition)
        assertEquals(3, definition.p2Team.size)
        assertEquals(listOf("fluttermane", "rillaboom", "amoonguss"),
            definition.p2Team.map { it.species })
        assertEquals(opponentIds.map(UUID::toString), definition.p2Team.map { it.uuid })
    }

    private fun hypothesis(
        selected: List<Int>,
        assignments: Map<UUID, Int>,
    ) = NativeOpponentRosterHypothesis(
        hypothesisId = "test:${selected.joinToString(",")}:${assignments.size}",
        probability = 1.0,
        selectedPreviewSlotIds = selected,
        revealedAssignments = assignments,
    )

    private fun preview(vararg species: String): BattleOpponentTeamPreviewView {
        val ids = if (species.isEmpty()) {
            arrayOf("fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus")
        } else {
            species
        }
        return BattleOpponentTeamPreviewView(
            selectionSize = 3,
            pokemon = ids.mapIndexed { slot, id -> BattleOpponentTeamPreviewPokemonView(
                previewSlotId = slot,
                speciesId = "cobblemon:$id",
                formId = "normal",
                level = 50,
                knownTypeIds = if (id == "rillaboom") setOf("grass") else emptySet(),
            ) },
        )
    }

    private fun state(
        vararg pokemon: BattlePokemonStateView,
        battleId: UUID = BATTLE,
        format: BattleFormat = BattleFormat.SINGLE,
        remainingOpponent: Int = if (format == BattleFormat.SINGLE) 3 else 4,
        observedEvents: List<BattleObservedEventView> = emptyList(),
    ) = BattleStateView(
        battleId = battleId,
        format = format,
        turn = 1,
        pokemon = pokemon.toList(),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(
            BattleSide.ALLY to pokemon.count { it.side == BattleSide.ALLY },
            BattleSide.OPPONENT to remainingOpponent,
        ),
        observedEvents = observedEvents,
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        species: String,
        combatStats: BattleCombatStatRangesView? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
        speciesId = species,
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = emptySet(),
        combatStats = combatStats,
    )

    private fun build(
        id: UUID,
        knowledge: NativeBuildKnowledge,
        ability: String,
        opponentMoveSet: NativeOpponentMoveSetHypothesis? = null,
    ) = NativePokemonBuildHypothesis(
        battlePokemonId = id,
        knowledge = knowledge,
        abilityId = ability,
        itemId = "",
        nature = "Serious",
        gender = "N",
        evs = EVS,
        ivs = IVS,
        opponentMoveSet = opponentMoveSet,
    )

    private fun guessed(slot: Int) = BattleOpponentMoveSlotView(
        slot = slot,
        moveId = null,
        group = BattleOpponentMoveGroup.OTHER,
        knowledge = BattleOpponentMoveKnowledge.GUESS,
        source = BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun resolveSpecies(speciesId: String, @Suppress("UNUSED_PARAMETER") formId: String?): String =
        speciesId.substringAfter(':')

    private companion object {
        val BATTLE: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val OTHER_BATTLE: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val LEAD: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val RIGHT_LEAD: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val PRIVATE_HIDDEN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-999999999999")
        val EVS = mapOf("hp" to 0, "atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0)
        val IVS = mapOf("hp" to 31, "atk" to 31, "def" to 31, "spa" to 31, "spd" to 31, "spe" to 31)
        val MOVE_DETAILS = BattleMoveCandidateView(
            "normal",
            BattleMoveDamageCategory.PHYSICAL,
            40.0,
            100.0,
            0,
            35,
        )
    }
}
