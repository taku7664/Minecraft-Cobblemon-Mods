package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentRosterHypothesisCompiler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentRosterIssueCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeOpponentRosterHypothesisCompilerTest {
    @Test
    fun `six preview slots produce every three selection containing the revealed lead`() {
        val lead = opponent("cobblemon:fluttermane")
        val preview = preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus")

        val result = NativeOpponentRosterHypothesisCompiler.compile(state(lead, remaining = 3), preview)

        assertTrue(result.issues.isEmpty())
        assertEquals(10, result.hypotheses.size)
        assertTrue(result.hypotheses.all { 0 in it.selectedPreviewSlotIds })
        assertTrue(result.hypotheses.all { it.revealedAssignments[lead.battlePokemonId] == 0 })
        assertEquals(1.0, result.hypotheses.sumOf { it.probability }, 1e-12)
        assertEquals(result.hypotheses.map { it.hypothesisId }.sorted(), result.hypotheses.map { it.hypothesisId })
    }

    @Test
    fun `unrevealed six preview slots produce all twenty three selections`() {
        val preview = preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus")

        val result = NativeOpponentRosterHypothesisCompiler.compile(state(remaining = 3), preview)

        assertTrue(result.issues.isEmpty())
        assertEquals(20, result.hypotheses.size)
        assertTrue(result.hypotheses.all { it.revealedAssignments.isEmpty() })
        assertEquals(1.0, result.hypotheses.sumOf { it.probability }, 1e-12)
    }

    @Test
    fun `six preview slots produce every four selection containing both double leads`() {
        val left = opponent("cobblemon:fluttermane", slot = 0)
        val right = opponent("cobblemon:urshifu", slot = 1)
        val preview = preview(4, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus")

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(left, right, remaining = 4, format = BattleFormat.DOUBLE),
            preview,
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(6, result.hypotheses.size)
        assertTrue(result.hypotheses.all { it.selectedPreviewSlotIds.containsAll(setOf(0, 1)) })
        assertEquals(1.0, result.hypotheses.sumOf { it.probability }, 1e-12)
    }

    @Test
    fun `six on six singles test battle keeps the complete preview roster`() {
        val lead = opponent("cobblemon:torterra")
        val preview = preview(6, "torterra", "zoroark", "celebi", "houndoom", "milotic", "togekiss")

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 6),
            preview,
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(1, result.hypotheses.size)
        assertEquals((0..5).toList(), result.hypotheses.single().selectedPreviewSlotIds)
        assertEquals(0, result.hypotheses.single().revealedAssignments[lead.battlePokemonId])
        assertEquals(1.0, result.hypotheses.single().probability, 1e-12)
    }

    @Test
    fun `singles accepts every selection size exposed by the preview`() {
        val species = arrayOf("torterra", "zoroark", "celebi", "houndoom", "milotic", "togekiss")
        for (selectionSize in 1..species.size) {
            val lead = opponent("cobblemon:torterra")
            val result = NativeOpponentRosterHypothesisCompiler.compile(
                state(lead, remaining = selectionSize),
                preview(selectionSize, *species),
            )

            assertTrue(result.issues.isEmpty(), "selectionSize=$selectionSize issues=${result.issues}")
            assertTrue(result.hypotheses.isNotEmpty(), "selectionSize=$selectionSize")
            assertTrue(result.hypotheses.all { hypothesis ->
                hypothesis.selectedPreviewSlotIds.size == selectionSize &&
                    0 in hypothesis.selectedPreviewSlotIds
            }, "selectionSize=$selectionSize hypotheses=${result.hypotheses}")
            assertEquals(1.0, result.hypotheses.sumOf { it.probability }, 1e-12)
        }
    }

    @Test
    fun `unsupported double selection size remains explicit`() {
        val left = opponent("cobblemon:fluttermane", slot = 0)
        val right = opponent("cobblemon:urshifu", slot = 1)

        val invalid = NativeOpponentRosterHypothesisCompiler.compile(
            state(left, right, remaining = 3, format = BattleFormat.DOUBLE),
            preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus"),
        )

        assertTrue(invalid.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.UNSUPPORTED_SELECTION_RULE),
            invalid.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `initial state rejects previously revealed inactive opponents`() {
        val lead = opponent("cobblemon:fluttermane")
        val priorBench = opponent("cobblemon:urshifu", slot = null)

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, priorBench, remaining = 3),
            preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus"),
        )

        assertTrue(result.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.INITIAL_REVEAL_LAYOUT_INVALID),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `turn one with an observed action is not an initial roster state`() {
        val lead = opponent("cobblemon:fluttermane")
        val event = BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = lead.battlePokemonId,
            publicValueId = "moonblast",
        )

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 3, events = listOf(event)),
            preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus"),
        )

        assertTrue(result.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.PUBLIC_STATE_NOT_INITIAL),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `turn zero lead presentation remains a valid opening observation`() {
        val lead = opponent("cobblemon:fluttermane")
        val presentation = BattleObservedEventView(
            sequence = 0,
            turn = 0,
            kind = BattleObservedEventKind.SWITCHED,
            actorPokemonId = lead.battlePokemonId,
        )

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 3, events = listOf(presentation)),
            preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus"),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(10, result.hypotheses.size)
    }

    @Test
    fun `turn zero move event cannot masquerade as an opening observation`() {
        val lead = opponent("cobblemon:fluttermane")
        val move = BattleObservedEventView(
            sequence = 0,
            turn = 0,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = lead.battlePokemonId,
            publicValueId = "moonblast",
        )

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 3, events = listOf(move)),
            preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus"),
        )

        assertTrue(result.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.PUBLIC_STATE_NOT_INITIAL),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `turn zero opening identity must belong to a current active pokemon`() {
        val lead = opponent("cobblemon:fluttermane")
        val inactiveAlly = ally("cobblemon:mew", slot = null)
        val impossiblePresentation = BattleObservedEventView(
            sequence = 0,
            turn = 0,
            kind = BattleObservedEventKind.SWITCHED,
            actorPokemonId = inactiveAlly.battlePokemonId,
        )

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, inactiveAlly, remaining = 3, events = listOf(impossiblePresentation)),
            preview(3, "fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus"),
        )

        assertTrue(result.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.PUBLIC_STATE_NOT_INITIAL),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `opaque ambiguity creates separate injective reveal mappings without reading live identity`() {
        val lead = opponent("cobblemon:zoroark")
        val preview = preview(3, "zoroark", "garchomp", "rotom", "mew", "ditto", "pikachu")

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 3),
            preview,
            compatiblePreviewSlots = mapOf(lead.battlePokemonId to setOf(0, 1)),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(setOf(0, 1), result.hypotheses.map {
            it.revealedAssignments.getValue(lead.battlePokemonId)
        }.toSet())
        assertTrue(result.hypotheses.none { hypothesis ->
            hypothesis.selectedPreviewSlotIds.size != hypothesis.selectedPreviewSlotIds.toSet().size
        })
    }

    @Test
    fun `illusion assignment keeps a selected bench capable of producing the public appearance`() {
        val disguisedLead = opponent("cobblemon:garchomp")
        val preview = preview(3, "garchomp", "zoroark", "rotom", "mew", "ditto", "pikachu")

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(disguisedLead, remaining = 3),
            preview,
            compatiblePreviewSlots = mapOf(disguisedLead.battlePokemonId to setOf(0, 1)),
        )

        assertTrue(result.issues.isEmpty())
        val illusionWorlds = result.hypotheses.filter {
            it.revealedAssignments.getValue(disguisedLead.battlePokemonId) == 1
        }
        assertEquals(4, illusionWorlds.size)
        assertTrue(illusionWorlds.all { 0 in it.selectedPreviewSlotIds })
        assertTrue(result.hypotheses.none {
            it.revealedAssignments.getValue(disguisedLead.battlePokemonId) == 1 &&
                0 !in it.selectedPreviewSlotIds
        })
    }

    @Test
    fun `incompatible double illusion appearances fail explicitly instead of returning no worlds`() {
        val left = opponent("cobblemon:garchomp", slot = 0)
        val right = opponent("cobblemon:dragonite", slot = 1)
        val preview = preview(4, "garchomp", "dragonite", "zoroark", "zoroarkhisui", "mew", "ditto")

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(left, right, remaining = 4, format = BattleFormat.DOUBLE),
            preview,
            compatiblePreviewSlots = mapOf(
                left.battlePokemonId to setOf(2),
                right.battlePokemonId to setOf(3),
            ),
        )

        assertTrue(result.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `two revealed pokemon cannot share one preview slot`() {
        val left = opponent("cobblemon:zoroark", slot = 0)
        val right = opponent("cobblemon:zoroark", slot = 1)
        val preview = preview(4, "zoroark", "garchomp", "rotom", "mew", "ditto", "pikachu")

        val result = NativeOpponentRosterHypothesisCompiler.compile(
            state(left, right, remaining = 4, format = BattleFormat.DOUBLE),
            preview,
            compatiblePreviewSlots = mapOf(
                left.battlePokemonId to setOf(0),
                right.battlePokemonId to setOf(0),
            ),
        )

        assertTrue(result.hypotheses.isEmpty())
        assertEquals(
            setOf(NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `preview input order does not change hypotheses`() {
        val lead = opponent("cobblemon:fluttermane")
        val species = arrayOf("fluttermane", "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus")

        val forwardPreview = preview(3, *species)
        val reversePreview = BattleOpponentTeamPreviewView(3, forwardPreview.pokemon.reversed())
        val forward = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 3),
            forwardPreview,
        )
        val reverse = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 3),
            reversePreview,
        )

        assertEquals(forward.hypotheses, reverse.hypotheses)
    }

    @Test
    fun `missing preview identity or noninitial board fails explicitly`() {
        val lead = opponent("cobblemon:fluttermane")
        val preview = preview(3, "urshifu", "rillaboom", "incineroar", "amoonguss", "landorus", "garchomp")

        val missing = NativeOpponentRosterHypothesisCompiler.compile(state(lead, remaining = 3), preview)
        val later = NativeOpponentRosterHypothesisCompiler.compile(
            state(lead, remaining = 2, turn = 2),
            preview(3, "fluttermane", "urshifu", "rillaboom", "amoonguss", "landorus", "garchomp"),
        )

        assertEquals(setOf(NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE), missing.issues.map { it.code }.toSet())
        assertEquals(setOf(NativeOpponentRosterIssueCode.PUBLIC_STATE_NOT_INITIAL), later.issues.map { it.code }.toSet())
        assertTrue(missing.hypotheses.isEmpty())
        assertTrue(later.hypotheses.isEmpty())
    }

    private fun preview(selectionSize: Int, vararg species: String) = BattleOpponentTeamPreviewView(
        selectionSize = selectionSize,
        pokemon = species.mapIndexed { slot, id -> BattleOpponentTeamPreviewPokemonView(
            previewSlotId = slot,
            speciesId = "cobblemon:$id",
            formId = "normal",
            level = 50,
            knownTypeIds = emptySet(),
        ) },
    )

    private fun opponent(species: String, slot: Int? = 0) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = BattleSide.OPPONENT,
        activeSlot = slot,
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
    )

    private fun ally(species: String, slot: Int? = 0) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = BattleSide.ALLY,
        activeSlot = slot,
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
    )

    private fun state(
        vararg opponents: BattlePokemonStateView,
        remaining: Int,
        turn: Int = 1,
        format: BattleFormat = BattleFormat.SINGLE,
        events: List<BattleObservedEventView> = emptyList(),
    ) = BattleStateView(
        battleId = BATTLE,
        format = format,
        turn = turn,
        pokemon = opponents.toList(),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to remaining),
        observedEvents = events,
        inferences = emptyList(),
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
