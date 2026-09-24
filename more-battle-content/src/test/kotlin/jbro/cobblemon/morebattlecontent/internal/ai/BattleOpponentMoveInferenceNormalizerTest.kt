package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleOpponentMoveInferenceNormalizerTest {
    @Test
    fun `introductory and standard slots do not change with the hidden set`() {
        val first = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val second = mapOf(OPPONENT_ID to setOf("shadowball", "mysticalfire", "protect", "substitute"))

        listOf(BattleTrainerTier.INTRODUCTORY, BattleTrainerTier.STANDARD).forEach { tier ->
            val left = normalize(tier, first)
            val right = normalize(tier, second)
            assertEquals(signatures(left), signatures(right), tier.name)
            assertTrue(left.slots.filter { it.knowledge == BattleOpponentMoveKnowledge.GUESS }
                .all { it.moveId == null && it.details == null })
        }
    }

    @Test
    fun `introductory and standard never resolve a hidden move template`() {
        val state = state()
        val catalog = catalog(state)
        val hidden = mapOf(OPPONENT_ID to setOf("hidden-only"))
        listOf(BattleTrainerTier.INTRODUCTORY, BattleTrainerTier.STANDARD).forEach { tier ->
            val result = BattleOpponentMoveInferenceNormalizer.normalize(
                state,
                catalog,
                tier,
                hidden,
                moveDetails = { error("$tier attempted to resolve a hidden move") },
            )
            assertEquals(4, result.single().slots.size)
        }
    }

    @Test
    fun `the four policy stages expose only their configured evidence`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))

        val introductory = normalize(BattleTrainerTier.INTRODUCTORY, actual)
        assertEquals(1, introductory.slots.count { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED })
        assertEquals(BattleOpponentMoveGroup.STAB_ATTACK, introductory.slots.first().group)

        val standard = normalize(BattleTrainerTier.STANDARD, actual)
        assertEquals(1, standard.slots.count { it.group == BattleOpponentMoveGroup.STAB_ATTACK &&
            it.knowledge == BattleOpponentMoveKnowledge.EXPECTED })
        assertEquals(1, standard.slots.count { it.group == BattleOpponentMoveGroup.COVERAGE_ATTACK &&
            it.knowledge == BattleOpponentMoveKnowledge.EXPECTED })
        assertEquals(1, standard.slots.count { it.group == BattleOpponentMoveGroup.STATUS_OTHER &&
            it.knowledge == BattleOpponentMoveKnowledge.GUESS })

        val advanced = normalize(BattleTrainerTier.ADVANCED, actual)
        assertEquals(setOf("moonblast"), advanced.slots.filter {
            it.source == BattleOpponentMoveSource.DIFFICULTY_SET_READ
        }.mapTo(linkedSetOf()) { it.moveId })
        assertEquals(2, advanced.slots.count { it.group == BattleOpponentMoveGroup.STATUS_OTHER &&
            it.knowledge == BattleOpponentMoveKnowledge.GUESS })

        val boss = normalize(BattleTrainerTier.BOSS, actual)
        assertEquals(setOf("moonblast", "calmmind"), boss.slots.filter {
            it.source == BattleOpponentMoveSource.DIFFICULTY_SET_READ
        }.mapTo(linkedSetOf()) { it.moveId })
        assertFalse(boss.slots.any { it.moveId == "thunderwave" })
        assertEquals(1, boss.slots.count { it.group == BattleOpponentMoveGroup.STATUS_OTHER &&
            it.knowledge == BattleOpponentMoveKnowledge.GUESS })
    }

    @Test
    fun `a reveal upgrades the matching expected slot before replacing a group guess`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val initial = normalize(BattleTrainerTier.STANDARD, actual)
        val expectedMove = initial.slots.single { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED &&
            it.group == BattleOpponentMoveGroup.STAB_ATTACK }.moveId!!
        val revealedState = state(setOf(expectedMove, "thunderwave"))
        val updated = BattleOpponentMoveInferenceNormalizer.normalize(
            revealedState,
            catalog(revealedState),
            BattleTrainerTier.STANDARD,
            actual,
            mapOf(OPPONENT_ID to initial),
            moveDetails = MOVES::get,
        ).single()

        assertEquals(BattleOpponentMoveKnowledge.CONFIRMED,
            updated.slots.single { it.moveId == expectedMove }.knowledge)
        assertEquals(BattleOpponentMoveSource.PUBLIC_REVEAL,
            updated.slots.single { it.moveId == expectedMove }.source)
        assertEquals(BattleOpponentMoveKnowledge.CONFIRMED,
            updated.slots.single { it.moveId == "thunderwave" }.knowledge)
        assertEquals(4, updated.slots.size)
    }

    @Test
    fun `temporary transformed moves do not confirm an original set slot`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val initial = normalize(BattleTrainerTier.STANDARD, actual)
        val transformedState = state(setOf("mysticalfire"))
        val updated = BattleOpponentMoveInferenceNormalizer.normalize(
            transformedState,
            catalog(transformedState),
            BattleTrainerTier.STANDARD,
            actual,
            previous = mapOf(OPPONENT_ID to initial),
            ignoredRevealPokemonIds = setOf(OPPONENT_ID),
            moveDetails = MOVES::get,
        ).single()

        assertEquals(signatures(initial), signatures(updated))
        assertFalse(updated.slots.any { it.moveId == "mysticalfire" })
    }

    @Test
    fun `ledger rebuilds slots when the same battle pokemon changes form`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val ledger = BattleOpponentMoveInferenceLedger(MOVES::get)
        val normal = state(revealed = setOf("moonblast"), formId = "normal")
        val first = ledger.update(normal, catalog(normal), BattleTrainerTier.STANDARD, actual).single()
        val changed = state(formId = "sunny")
        val rebuilt = ledger.update(changed, catalog(changed), BattleTrainerTier.STANDARD, actual).single()

        assertEquals(BattleOpponentMoveKnowledge.CONFIRMED,
            first.slots.single { it.moveId == "moonblast" }.knowledge)
        assertFalse(rebuilt.slots.any { it.knowledge == BattleOpponentMoveKnowledge.CONFIRMED })
    }

    @Test
    fun `a self stat setup move may include a drawback stage`() {
        val shellSmash = BattleMoveCandidateView(
            "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 24,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(
                    BattleMoveEffectKind.STAT_STAGE,
                    BattleMoveEffectTarget.USER,
                    statStages = mapOf("attack" to 2, "speed" to 2, "defence" to -1),
                )),
                scriptedBehavior = false,
            ),
        )

        assertEquals(BattleOpponentMoveGroup.PURE_SETUP,
            BattleOpponentMoveInferenceNormalizer.group(state().pokemon.single(), shellSmash))
    }

    private fun normalize(
        tier: BattleTrainerTier,
        actual: Map<UUID, Set<String>>,
    ): BattleOpponentMoveInferenceView {
        val state = state()
        return BattleOpponentMoveInferenceNormalizer.normalize(
            state, catalog(state), tier, actual, moveDetails = MOVES::get,
        ).single()
    }

    private fun catalog(state: BattleStateView): BattlePublicActionCatalogView {
        val pokemon = state.pokemon.single()
        val revealed = pokemon.knownMoveIds.map { id ->
            BattlePublicMoveOptionView(id, requireNotNull(MOVES[id]), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        }
        return BattlePublicActionCatalogView(
            entries = listOf(BattlePokemonActionCatalogView(OPPONENT_ID, revealed)),
            candidatePools = listOf(BattlePublicMoveCandidatePoolView(
                OPPONENT_ID, "flutter-mane", "normal", MOVES.keys, "fixture:learnset", MOVES,
            )),
        )
    }

    private fun state(
        revealed: Set<String> = emptySet(),
        formId: String = "normal",
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(BattlePokemonStateView(
            battlePokemonId = OPPONENT_ID,
            side = BattleSide.OPPONENT,
            activeSlot = 0,
            speciesId = "flutter-mane",
            formId = formId,
            level = 50,
            hpFraction = 1.0,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = revealed,
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = false,
            knownTypeIds = setOf("ghost", "fairy"),
        )),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun signatures(view: BattleOpponentMoveInferenceView) = view.slots.map {
        listOf(it.slot, it.moveId, it.group, it.knowledge, it.source)
    }

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val MOVES = linkedMapOf(
            "moonblast" to move("fairy", BattleMoveDamageCategory.SPECIAL, 95.0),
            "shadowball" to move("ghost", BattleMoveDamageCategory.SPECIAL, 80.0),
            "powergem" to move("rock", BattleMoveDamageCategory.SPECIAL, 80.0),
            "mysticalfire" to move("fire", BattleMoveDamageCategory.SPECIAL, 75.0),
            "calmmind" to setup(),
            "thunderwave" to move("electric", BattleMoveDamageCategory.STATUS, 0.0),
            "protect" to move("normal", BattleMoveDamageCategory.STATUS, 0.0),
            "substitute" to move("normal", BattleMoveDamageCategory.STATUS, 0.0),
        )

        fun move(type: String, category: BattleMoveDamageCategory, power: Double) =
            BattleMoveCandidateView(type, category, power, 100.0, 0, 16)

        fun setup() = BattleMoveCandidateView(
            "psychic", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 16,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(
                    BattleMoveEffectKind.STAT_STAGE,
                    BattleMoveEffectTarget.USER,
                    statStages = mapOf("specialattack" to 1, "specialdefence" to 1),
                )),
                scriptedBehavior = false,
            ),
        )
    }
}
