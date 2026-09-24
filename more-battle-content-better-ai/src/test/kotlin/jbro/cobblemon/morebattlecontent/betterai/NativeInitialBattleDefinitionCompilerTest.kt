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
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinitionIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleWorldHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBuildKnowledge
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialBattleDefinitionCompiler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentMoveSetHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentMoveSlotHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonBuildHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePublicPokemonIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeInitialBattleDefinitionCompilerTest {
    @Test
    fun `complete public opening compiles one native world without guess slots`() {
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(),
            catalog = catalog(),
            identities = identities(),
            world = world(),
            seed = SEED,
        )

        assertEquals(emptyList<Any>(), result.issues)
        assertNotNull(result.definition)
        val definition = requireNotNull(result.definition)
        assertEquals("cobblemonsingles", definition.formatId)
        assertEquals(SEED, definition.seed)
        assertEquals(listOf("thunderbolt", "protect"), definition.p1Team.single().moves)
        assertEquals(listOf("moonblast"), definition.p2Team.single().moves)
        assertEquals("static", definition.p1Team.single().ability)
        assertEquals("choicespecs", definition.p2Team.single().item)
        assertEquals("Timid", definition.p2Team.single().nature)
        assertEquals("N", definition.p2Team.single().gender)
        assertEquals(EVS, definition.p2Team.single().evs)
        assertEquals(IVS, definition.p2Team.single().ivs)
        assertNull(definition.openingState)
    }

    @Test
    fun `opponent moves are bound to the world and cannot be replaced by a later catalog`() {
        val boundMoves = NativeOpponentMoveSetHypothesis(
            OPPONENT,
            listOf(
                nativeExpected(0, "moonblast"),
                nativeGuess(1),
                nativeGuess(2),
                nativeGuess(3),
            ),
        )
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(),
            catalog = catalog(opponentSlots = listOf(
                expected(0, "powergem"),
                guess(1),
                guess(2),
                guess(3),
            )),
            identities = identities(),
            world = world(opponentBuild = build(
                OPPONENT,
                NativeBuildKnowledge.PUBLIC_HYPOTHESIS,
                "protosynthesis",
                "choicespecs",
                opponentMoveSet = boundMoves,
            )),
            seed = SEED,
        )

        assertEquals(emptyList<Any>(), result.issues)
        assertEquals(listOf("moonblast"), requireNotNull(result.definition).p2Team.single().moves)
    }

    @Test
    fun `a world that omits an already public opponent move is rejected as stale`() {
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(opponentKnownMoves = setOf("cobblemon:power_gem")),
            catalog = catalog(),
            identities = identities(),
            world = world(),
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.PUBLIC_MOVE_CONFLICT),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `hidden or omitted opponent bench is rejected instead of deleting switch branches`() {
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(
                opponentSpecies = "showdown:unknown",
                remainingOpponent = 2,
            ),
            catalog = catalog(),
            identities = identities(opponentSpecies = "showdown:unknown", opponentShowdownSpecies = "unknown"),
            world = world(),
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(
                NativeBattleDefinitionIssueCode.PUBLIC_ROSTER_INCOMPLETE,
                NativeBattleDefinitionIssueCode.UNKNOWN_PUBLIC_SPECIES,
            ),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `mid battle public state is rejected instead of being reconstructed incompletely`() {
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(allyHp = 0.5, allyStages = mapOf("attack" to 1)),
            catalog = catalog(),
            identities = identities(),
            world = world(),
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.PUBLIC_STATE_NOT_INITIAL),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `build source and revealed item must agree with public state`() {
        val badWorld = world(
            allyBuild = build(ALLY, NativeBuildKnowledge.PUBLIC_HYPOTHESIS, "lightningrod", "leftovers"),
            opponentBuild = build(
                OPPONENT,
                NativeBuildKnowledge.EXACT_OWN,
                "protosynthesis",
                "leftovers",
                defaultOpponentMoveSet(),
            ),
        )
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(opponentAbility = "protosynthesis", opponentItem = "choicespecs"),
            catalog = catalog(),
            identities = identities(),
            world = badWorld,
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(
                NativeBattleDefinitionIssueCode.BUILD_KNOWLEDGE_MISMATCH,
                NativeBattleDefinitionIssueCode.PUBLIC_ITEM_CONFLICT,
            ),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `opening item reveal constrains builds before dto fields are folded`() {
        val reveals = listOf(
            BattleObservedEventView(
                sequence = 0,
                turn = 0,
                kind = BattleObservedEventKind.HELD_ITEM_REVEALED,
                actorPokemonId = OPPONENT,
                publicValueId = "choicespecs",
            ),
        )
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(events = reveals),
            catalog = catalog(),
            identities = identities(),
            world = world(opponentBuild = build(
                OPPONENT,
                NativeBuildKnowledge.PUBLIC_HYPOTHESIS,
                "pressure",
                "leftovers",
                defaultOpponentMoveSet(),
            )),
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.PUBLIC_ITEM_CONFLICT),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `trace current ability does not reject its different source set ability`() {
        val traceReveal = BattleObservedEventView(
            sequence = 0,
            turn = 0,
            kind = BattleObservedEventKind.ABILITY_REVEALED,
            actorPokemonId = OPPONENT,
            publicValueId = "intimidate",
        )
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(opponentAbility = "intimidate", events = listOf(traceReveal)),
            catalog = catalog(),
            identities = identities(),
            world = world(opponentBuild = build(
                OPPONENT,
                NativeBuildKnowledge.PUBLIC_HYPOTHESIS,
                "trace",
                "choicespecs",
                defaultOpponentMoveSet(),
            )),
            seed = SEED,
        )

        assertEquals(emptyList<Any>(), result.issues)
        assertEquals("trace", requireNotNull(result.definition).p2Team.single().ability)
    }

    @Test
    fun `guess only opponent slots cannot be materialized as a fake native move`() {
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(),
            catalog = catalog(opponentSlots = listOf(guess(0), guess(1))),
            identities = identities(),
            world = world(opponentBuild = build(
                OPPONENT,
                NativeBuildKnowledge.PUBLIC_HYPOTHESIS,
                "protosynthesis",
                "choicespecs",
                opponentMoveSet = null,
            )),
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `own moves that collapse to one Showdown id are rejected`() {
        val result = NativeInitialBattleDefinitionCompiler.compile(
            state = state(),
            catalog = catalog(
                allyMoves = listOf(ownMove("Thunder-Bolt"), ownMove("thunderbolt")),
            ),
            identities = identities(),
            world = world(),
            seed = SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `incomplete or non exact own move catalog is rejected`() {
        val incomplete = NativeInitialBattleDefinitionCompiler.compile(
            state(),
            catalog(allyMoveSetComplete = false),
            identities(),
            world(),
            SEED,
        )
        val wrongKnowledge = NativeInitialBattleDefinitionCompiler.compile(
            state(),
            catalog(allyMoves = listOf(ownMove("thunderbolt", knowledge = BattlePublicMoveKnowledge.PUBLICLY_REVEALED))),
            identities(),
            world(),
            SEED,
        )

        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE),
            incomplete.issues.mapTo(linkedSetOf()) { it.code },
        )
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE),
            wrongKnowledge.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `identity resolved from a different public species is rejected as stale`() {
        val stale = identities().map { identity ->
            if (identity.battlePokemonId == OPPONENT) identity.copy(publicSpeciesId = "showdown:ironbundle") else identity
        }

        val result = NativeInitialBattleDefinitionCompiler.compile(state(), catalog(), stale, world(), SEED)

        assertNull(result.definition)
        assertEquals(
            setOf(NativeBattleDefinitionIssueCode.PUBLIC_IDENTITY_STALE),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `world missing one public Pokemon is rejected as a roster mismatch`() {
        val incompleteWorld = NativeBattleWorldHypothesis(
            "world-incomplete",
            1.0,
            listOf(build(ALLY, NativeBuildKnowledge.EXACT_OWN, "static", "")),
        )

        val result = NativeInitialBattleDefinitionCompiler.compile(
            state(),
            catalog(),
            identities(),
            incompleteWorld,
            SEED,
        )

        assertNull(result.definition)
        assertEquals(
            setOf(
                NativeBattleDefinitionIssueCode.HYPOTHESIS_ROSTER_MISMATCH,
                NativeBattleDefinitionIssueCode.BUILD_HYPOTHESIS_MISSING,
            ),
            result.issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    private fun state(
        opponentSpecies: String = "showdown:fluttermane",
        remainingOpponent: Int = 1,
        allyHp: Double = 1.0,
        allyStages: Map<String, Int> = emptyMap(),
        opponentAbility: String? = null,
        opponentItem: String? = null,
        opponentKnownMoves: Set<String> = emptySet(),
        events: List<BattleObservedEventView> = emptyList(),
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(
                id = ALLY,
                side = BattleSide.ALLY,
                species = "cobblemon:pikachu",
                hp = allyHp,
                stages = allyStages,
                ability = "static",
                item = null,
                stats = BattleCombatStatRangesView.exact(110, 75, 60, 70, 70, 110),
            ),
            pokemon(
                id = OPPONENT,
                side = BattleSide.OPPONENT,
                species = opponentSpecies,
                hp = 1.0,
                ability = opponentAbility,
                item = opponentItem,
                stats = null,
                knownMoves = opponentKnownMoves,
            ),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to remainingOpponent),
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        species: String,
        hp: Double,
        stages: Map<String, Int> = emptyMap(),
        ability: String?,
        item: String?,
        stats: BattleCombatStatRangesView?,
        knownMoves: Set<String> = emptySet(),
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = species,
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = null,
        statStages = stages,
        knownMoveIds = knownMoves,
        knownAbilityId = ability,
        knownHeldItemId = item,
        fainted = false,
        knownTypeIds = emptySet(),
        combatStats = stats,
    )

    private fun catalog(
        allyMoves: List<BattlePublicMoveOptionView> = listOf(
            ownMove("thunderbolt"),
            ownMove("protect", BattleMoveDamageCategory.STATUS),
        ),
        opponentSlots: List<BattleOpponentMoveSlotView> = listOf(
            expected(0, "moonblast"),
            guess(1),
        ),
        allyMoveSetComplete: Boolean = true,
    ) = BattlePublicActionCatalogView(
        entries = listOf(
            BattlePokemonActionCatalogView(
                ALLY,
                allyMoves,
                moveSetComplete = allyMoveSetComplete,
            ),
        ),
        opponentMoveInferences = listOf(BattleOpponentMoveInferenceView(OPPONENT, opponentSlots)),
    )

    private fun ownMove(
        moveId: String,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
        knowledge: BattlePublicMoveKnowledge = BattlePublicMoveKnowledge.EXACT_OWN,
    ) =
        BattlePublicMoveOptionView(
            moveId,
            details.copy(damageCategory = category, power = if (category == BattleMoveDamageCategory.STATUS) 0.0 else 90.0),
            knowledge,
        )

    private fun expected(slot: Int, moveId: String) = BattleOpponentMoveSlotView(
        slot,
        moveId,
        BattleOpponentMoveGroup.STAB_ATTACK,
        BattleOpponentMoveKnowledge.EXPECTED,
        BattleOpponentMoveSource.LEARNSET_EXPECTATION,
        details,
    )

    private fun guess(slot: Int) = BattleOpponentMoveSlotView(
        slot,
        null,
        BattleOpponentMoveGroup.STATUS_OTHER,
        BattleOpponentMoveKnowledge.GUESS,
        BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun identities(
        opponentSpecies: String = "showdown:fluttermane",
        opponentShowdownSpecies: String = "fluttermane",
    ) = listOf(
        NativePublicPokemonIdentity(ALLY, "cobblemon:pikachu", null, "pikachu"),
        NativePublicPokemonIdentity(OPPONENT, opponentSpecies, null, opponentShowdownSpecies),
    )

    private fun world(
        allyBuild: NativePokemonBuildHypothesis = build(
            ALLY,
            NativeBuildKnowledge.EXACT_OWN,
            "static",
            "",
        ),
        opponentBuild: NativePokemonBuildHypothesis = build(
            OPPONENT,
            NativeBuildKnowledge.PUBLIC_HYPOTHESIS,
            "protosynthesis",
            "choicespecs",
            defaultOpponentMoveSet(),
        ),
    ) = NativeBattleWorldHypothesis("world-1", 1.0, listOf(allyBuild, opponentBuild))

    private fun build(
        id: UUID,
        knowledge: NativeBuildKnowledge,
        ability: String,
        item: String,
        opponentMoveSet: NativeOpponentMoveSetHypothesis? = null,
    ) = NativePokemonBuildHypothesis(
        battlePokemonId = id,
        knowledge = knowledge,
        abilityId = ability,
        itemId = item,
        nature = "Timid",
        gender = "N",
        evs = EVS,
        ivs = IVS,
        opponentMoveSet = opponentMoveSet,
    )

    private fun nativeExpected(slot: Int, moveId: String) = NativeOpponentMoveSlotHypothesis(
        slot,
        moveId,
        BattleOpponentMoveGroup.STAB_ATTACK,
        BattleOpponentMoveKnowledge.EXPECTED,
        BattleOpponentMoveSource.LEARNSET_EXPECTATION,
    )

    private fun nativeGuess(slot: Int) = NativeOpponentMoveSlotHypothesis(
        slot,
        null,
        BattleOpponentMoveGroup.STATUS_OTHER,
        BattleOpponentMoveKnowledge.GUESS,
        BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun defaultOpponentMoveSet() = NativeOpponentMoveSetHypothesis(
        OPPONENT,
        listOf(
            nativeExpected(0, "moonblast"),
            nativeGuess(1),
            nativeGuess(2),
            nativeGuess(3),
        ),
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val SEED = listOf(1, 2, 3, 4)
        val EVS = mapOf("hp" to 0, "atk" to 0, "def" to 4, "spa" to 252, "spd" to 0, "spe" to 252)
        val IVS = mapOf("hp" to 31, "atk" to 0, "def" to 31, "spa" to 31, "spd" to 31, "spe" to 31)
        val details = BattleMoveCandidateView("fairy", BattleMoveDamageCategory.SPECIAL, 80.0, 100.0, 0, 16)
    }
}
