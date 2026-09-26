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
    fun `initial public reveals still count toward the actual attack status shape`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val revealedState = state(setOf("moonblast"))

        val result = BattleOpponentMoveInferenceNormalizer.normalize(
            revealedState,
            catalog(revealedState),
            BattleTrainerTier.ADVANCED,
            actual,
            moveDetails = MOVES::get,
        ).single()

        assertEquals(2, result.slots.count { it.group.isAttackForTest() })
        assertEquals(2, result.slots.count { !it.group.isAttackForTest() })
        assertTrue(result.slots.any { it.moveId == "powergem" })
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
    fun `ledger rebuilds non confirmed slots when the same battle pokemon changes public types`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val ledger = BattleOpponentMoveInferenceLedger(MOVES::get)
        val initial = state(revealed = setOf("thunderwave"))
        val before = ledger.update(initial, catalog(initial), BattleTrainerTier.STANDARD, actual).single()
        val changed = state(revealed = setOf("thunderwave"), types = setOf("rock"))

        val rebuilt = ledger.update(changed, catalog(changed), BattleTrainerTier.STANDARD, actual).single()

        assertEquals(
            before.slots.single { it.moveId == "thunderwave" }.slot,
            rebuilt.slots.single { it.moveId == "thunderwave" }.slot,
            "a public reveal must keep its logical slot while the remaining hypotheses are rebuilt",
        )
        assertEquals(BattleOpponentMoveKnowledge.CONFIRMED,
            rebuilt.slots.single { it.moveId == "thunderwave" }.knowledge)
        assertEquals(BattleOpponentMoveGroup.STAB_ATTACK,
            rebuilt.slots.single { it.moveId == "powergem" }.group)
        assertEquals(BattleOpponentMoveGroup.COVERAGE_ATTACK,
            rebuilt.slots.single { it.moveId == "moonblast" }.group)
        assertFalse(rebuilt.slots.any {
            it.moveId == "moonblast" && it.group == BattleOpponentMoveGroup.STAB_ATTACK
        })
    }

    @Test
    fun `tera defensive type does not erase the original offensive stab types`() {
        val pokemon = state(
            types = setOf("fire"),
            baseStabTypes = setOf("ghost", "fairy"),
            teraType = "fire",
        ).pokemon.single()

        assertEquals(
            BattleOpponentMoveGroup.STAB_ATTACK,
            BattleOpponentMoveInferenceNormalizer.group(pokemon, requireNotNull(MOVES["moonblast"])),
        )
        assertEquals(
            BattleOpponentMoveGroup.STAB_ATTACK,
            BattleOpponentMoveInferenceNormalizer.group(pokemon, requireNotNull(MOVES["shadowball"])),
        )
        assertEquals(
            BattleOpponentMoveGroup.STAB_ATTACK,
            BattleOpponentMoveInferenceNormalizer.group(pokemon, requireNotNull(MOVES["mysticalfire"])),
        )
        assertEquals(
            BattleOpponentMoveGroup.COVERAGE_ATTACK,
            BattleOpponentMoveInferenceNormalizer.group(pokemon, requireNotNull(MOVES["powergem"])),
        )
    }

    @Test
    fun `ledger keeps the original slots while a pokemon is temporarily transformed`() {
        val actual = mapOf(OPPONENT_ID to setOf("moonblast", "powergem", "calmmind", "thunderwave"))
        val ledger = BattleOpponentMoveInferenceLedger(MOVES::get)
        val original = state()
        val before = ledger.update(original, catalog(original), BattleTrainerTier.STANDARD, actual).single()
        val transformed = state(
            revealed = setOf("mysticalfire"),
            speciesId = "temporary-copy",
            formId = "copied",
            types = setOf("fire"),
        )

        val duringTransform = ledger.update(
            transformed,
            catalog(transformed),
            BattleTrainerTier.STANDARD,
            actual,
            ignoredRevealPokemonIds = setOf(OPPONENT_ID),
        ).single()

        assertEquals(signatures(before), signatures(duringTransform))
        assertFalse(duringTransform.slots.any { it.moveId == "mysticalfire" })
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
                OPPONENT_ID, pokemon.speciesId, pokemon.formId, MOVES.keys, "fixture:learnset", MOVES,
            )),
        )
    }

    private fun state(
        revealed: Set<String> = emptySet(),
        speciesId: String = "flutter-mane",
        formId: String = "normal",
        types: Set<String> = setOf("ghost", "fairy"),
        baseStabTypes: Set<String> = types,
        teraType: String? = null,
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(BattlePokemonStateView(
            battlePokemonId = OPPONENT_ID,
            side = BattleSide.OPPONENT,
            activeSlot = 0,
            speciesId = speciesId,
            formId = formId,
            level = 50,
            hpFraction = 1.0,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = revealed,
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = false,
            knownTypeIds = types,
            knownVolatileEffectIds = emptySet(),
            knownBaseStabTypeIds = baseStabTypes,
            knownTeraTypeId = teraType,
        )),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun signatures(view: BattleOpponentMoveInferenceView) = view.slots.map {
        listOf(it.slot, it.moveId, it.group, it.knowledge, it.source)
    }

    private fun BattleOpponentMoveGroup.isAttackForTest(): Boolean =
        this == BattleOpponentMoveGroup.STAB_ATTACK || this == BattleOpponentMoveGroup.COVERAGE_ATTACK

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
