package jbro.cobblemon.morebattlecontent.api.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BattleStateViewContractTest {
    private val allyId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val opponentId = UUID.fromString("20000000-0000-0000-0000-000000000002")

    @Test
    fun `field effects distinguish exact ranged and unknown remaining turns`() {
        val field = BattleFieldStateView(
            weather = BattleTimedEffectView(
                effectId = "rain",
                remainingTurns = null,
                remainingTurnsRange = BattleIntegerRange(5, 8),
            ),
            terrain = null,
            roomEffects = listOf(BattleTimedEffectView("trick_room", 3)),
            globalEffects = emptyList(),
            sideConditions = mapOf(
                BattleSide.ALLY to listOf(BattleTimedEffectView("stealth_rock", null)),
                BattleSide.OPPONENT to emptyList(),
            ),
        )

        assertEquals(null, field.weather?.remainingTurns)
        assertEquals(BattleIntegerRange(5, 8), field.weather?.remainingTurnsRange)
        assertEquals(3, field.roomEffects.single().remainingTurns)
        assertNull(field.roomEffects.single().remainingTurnsRange)
        assertNull(field.sideConditions.getValue(BattleSide.ALLY).single().remainingTurns)
        assertNull(field.sideConditions.getValue(BattleSide.ALLY).single().remainingTurnsRange)

        assertThrows(IllegalArgumentException::class.java) {
            BattleTimedEffectView(
                effectId = "rain",
                remainingTurns = 5,
                remainingTurnsRange = BattleIntegerRange(5, 8),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleTimedEffectView(
                effectId = "rain",
                remainingTurns = null,
                remainingTurnsRange = BattleIntegerRange(5, 5),
            )
        }
    }

    @Test
    fun `state carries ordered public observations and inference confidence`() {
        val state = state(
            observedEvents = listOf(
                BattleObservedEventView(
                    sequence = 10,
                    turn = 1,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = opponentId,
                    publicValueId = "protect",
                ),
                BattleObservedEventView(
                    sequence = 11,
                    turn = 1,
                    kind = BattleObservedEventKind.HELD_ITEM_REVEALED,
                    actorPokemonId = opponentId,
                    publicValueId = "leftovers",
                ),
            ),
            inferences = listOf(
                BattleInferenceView(
                    subjectPokemonId = opponentId,
                    categoryId = "speed_item",
                    candidateId = "choice_scarf",
                    confidence = BattleInferenceConfidence.LIKELY,
                    probabilityRange = BattleFractionRange(0.55, 0.75),
                    basis = setOf(BattleInferenceBasis.ACTION_ORDER, BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                    evidenceEventSequences = listOf(10),
                ),
            ),
        )

        assertEquals(listOf(10L, 11L), state.observedEvents.map { it.sequence })
        assertEquals(BattleInferenceConfidence.LIKELY, state.inferences.single().confidence)
        assertEquals(BattleFractionRange(0.55, 0.75), state.inferences.single().probabilityRange)
        assertEquals(listOf(10L), state.inferences.single().evidenceEventSequences)
    }

    @Test
    fun `public events carry bounded action window evidence without claiming raw speed or damage cause`() {
        val action = BattleObservedEventView(
            sequence = 20,
            turn = 2,
            kind = BattleObservedEventKind.ACTION_ORDER,
            actorPokemonId = opponentId,
            publicValueId = "quickattack",
            baseMovePriority = 1,
        )
        val damage = BattleObservedEventView(
            sequence = 22,
            turn = 2,
            kind = BattleObservedEventKind.HP_CHANGED,
            actorPokemonId = allyId,
            hpFractionDelta = -0.25,
            precedingActionSequence = 20,
            precedingActionActorPokemonId = opponentId,
            precedingActionMoveId = "quickattack",
        )
        val residual = BattleObservedEventView(
            sequence = 23,
            turn = 2,
            kind = BattleObservedEventKind.HP_CHANGED,
            actorPokemonId = opponentId,
            hpFractionDelta = -0.0625,
            publicSourceEffectId = "brn",
        )

        assertEquals(1, action.baseMovePriority)
        assertEquals(20, damage.precedingActionSequence)
        assertEquals(opponentId, damage.precedingActionActorPokemonId)
        assertEquals("quickattack", damage.precedingActionMoveId)
        assertEquals("brn", residual.publicSourceEffectId)
    }

    @Test
    fun `event causal fields reject ambiguous combinations`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleObservedEventView(
                sequence = 1,
                turn = 1,
                kind = BattleObservedEventKind.HP_CHANGED,
                actorPokemonId = allyId,
                hpFractionDelta = -0.2,
                precedingActionSequence = 0,
                precedingActionActorPokemonId = opponentId,
                precedingActionMoveId = "tackle",
                publicSourceEffectId = "brn",
            )
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleObservedEventView(
                sequence = 1,
                turn = 1,
                kind = BattleObservedEventKind.SWITCHED,
                actorPokemonId = allyId,
                baseMovePriority = 0,
            )
        }
    }

    @Test
    fun `action order relation identifies the public counterpart without embedding it in candidate text`() {
        val inference = BattleInferenceView(
            subjectPokemonId = opponentId,
            categoryId = "observed_action_order",
            candidateId = "BEFORE_AT_SAME_BASE_PRIORITY",
            confidence = BattleInferenceConfidence.CONFIRMED,
            basis = setOf(BattleInferenceBasis.ACTION_ORDER),
            evidenceEventSequences = listOf(10, 12),
            relatedPokemonId = allyId,
        )

        assertEquals(allyId, inference.relatedPokemonId)
        assertFalse(inference.candidateId!!.contains(allyId.toString()))
    }

    @Test
    fun `ability availability is restricted to ability inferences`() {
        val ability = BattleInferenceView(
            subjectPokemonId = opponentId,
            categoryId = "ability",
            candidateId = "sandveil",
            confidence = BattleInferenceConfidence.POSSIBLE,
            basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
            abilityAvailability = BattleAbilityAvailability.HIDDEN,
        )

        assertEquals(BattleAbilityAvailability.HIDDEN, ability.abilityAvailability)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ability.copy(categoryId = "held_item")
        }
    }

    @Test
    fun `public move outcomes preserve declared facts without claiming a damage cause`() {
        val critical = BattleObservedEventView(
            sequence = 30,
            turn = 3,
            kind = BattleObservedEventKind.MOVE_OUTCOME,
            targetPokemonIds = listOf(allyId),
            moveOutcome = BattleMoveOutcomeView(BattleMoveOutcomeKind.CRITICAL_HIT),
        )
        val hitCount = BattleObservedEventView(
            sequence = 31,
            turn = 3,
            kind = BattleObservedEventKind.MOVE_OUTCOME,
            targetPokemonIds = listOf(allyId),
            moveOutcome = BattleMoveOutcomeView(BattleMoveOutcomeKind.HIT_COUNT, hitCount = 4),
        )
        val substitute = BattleObservedEventView(
            sequence = 32,
            turn = 3,
            kind = BattleObservedEventKind.MOVE_OUTCOME,
            targetPokemonIds = listOf(allyId),
            moveOutcome = BattleMoveOutcomeView(
                BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED,
                publicEffectId = "substitute",
            ),
        )
        val protection = BattleObservedEventView(
            sequence = 33,
            turn = 3,
            kind = BattleObservedEventKind.MOVE_OUTCOME,
            targetPokemonIds = listOf(allyId),
            moveOutcome = BattleMoveOutcomeView(
                BattleMoveOutcomeKind.PROTECTION_STARTED,
                publicEffectId = "protect",
            ),
        )

        assertEquals(BattleMoveOutcomeKind.CRITICAL_HIT, critical.moveOutcome?.kind)
        assertNull(critical.precedingActionSequence)
        assertEquals(4, hitCount.moveOutcome?.hitCount)
        assertEquals("substitute", substitute.moveOutcome?.publicEffectId)
        assertNull(substitute.moveOutcome?.moveId)
        assertEquals("protect", protection.moveOutcome?.publicEffectId)
        assertNull(protection.moveOutcome?.moveId)
    }

    @Test
    fun `move outcome contract rejects missing counts and unrelated event kinds`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleMoveOutcomeView(BattleMoveOutcomeKind.HIT_COUNT)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleMoveOutcomeView(BattleMoveOutcomeKind.CRITICAL_HIT, hitCount = 2)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleMoveOutcomeView(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleMoveOutcomeView(
                BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED,
                publicEffectId = "protect",
            )
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleMoveOutcomeView(
                BattleMoveOutcomeKind.PROTECTION_STARTED,
                publicEffectId = "substitute",
            )
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleObservedEventView(
                sequence = 1,
                turn = 1,
                kind = BattleObservedEventKind.HP_CHANGED,
                actorPokemonId = allyId,
                hpFractionDelta = -0.2,
                moveOutcome = BattleMoveOutcomeView(BattleMoveOutcomeKind.RESISTED),
            )
        }
    }

    @Test
    fun `new move outcome event preserves existing public event ordinals`() {
        assertEquals(
            listOf(
                "ACTION_ORDER",
                "MOVE_USED",
                "SWITCHED",
                "FAINTED",
                "HP_CHANGED",
                "STATUS_CHANGED",
                "ABILITY_REVEALED",
                "HELD_ITEM_REVEALED",
                "FIELD_EFFECT_CHANGED",
            ),
            BattleObservedEventKind.entries.dropLast(1).map { it.name },
        )
        assertEquals(BattleObservedEventKind.MOVE_OUTCOME, BattleObservedEventKind.entries.last())
    }

    @Test
    fun `public defense outcomes append without changing existing outcome ordinals`() {
        assertEquals(
            listOf(
                "MISSED",
                "FAILED",
                "BLOCKED",
                "NO_TARGET",
                "CANNOT_ACT",
                "CRITICAL_HIT",
                "SUPER_EFFECTIVE",
                "RESISTED",
                "IMMUNE",
                "HIT_COUNT",
            ),
            BattleMoveOutcomeKind.entries.dropLast(2).map { it.name },
        )
        assertEquals(
            listOf("SUBSTITUTE_DAMAGED", "PROTECTION_STARTED"),
            BattleMoveOutcomeKind.entries.takeLast(2).map { it.name },
        )
    }

    @Test
    fun `state rejects incomplete side counts and unordered observations`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            state(remainingPokemonBySide = mapOf(BattleSide.ALLY to 3))
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            state(
                observedEvents = listOf(
                    BattleObservedEventView(2, 1, BattleObservedEventKind.SWITCHED, opponentId),
                    BattleObservedEventView(1, 1, BattleObservedEventKind.MOVE_USED, opponentId),
                ),
            )
        }
    }

    @Test
    fun `unknown inference cannot claim a hidden candidate`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleInferenceView(
                subjectPokemonId = opponentId,
                categoryId = "held_item",
                candidateId = "choice_scarf",
                confidence = BattleInferenceConfidence.UNKNOWN,
            )
        }
    }

    @Test
    fun `public Pokemon view exposes known facts but no hidden build fields`() {
        val propertyNames = BattlePokemonStateView::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("actualMoveIds" in propertyNames)
        assertFalse("actualAbilityId" in propertyNames)
        assertFalse("actualHeldItemId" in propertyNames)
        assertFalse("natureId" in propertyNames)
        assertFalse("ivs" in propertyNames)
        assertFalse("evs" in propertyNames)
    }

    @Test
    fun `public Pokemon view may expose bounded combat stats without build inputs`() {
        val view = pokemon(opponentId, BattleSide.OPPONENT, BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(140, 187),
            attack = BattleIntegerRange(94, 167),
            defence = BattleIntegerRange(85, 150),
            specialAttack = BattleIntegerRange(80, 145),
            specialDefence = BattleIntegerRange(90, 155),
            speed = BattleIntegerRange(75, 140),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ))

        assertEquals(BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE, view.combatStats?.knowledge)
        assertFalse(BattleCombatStatRangesView::class.java.declaredFields.any {
            it.name.contains("nature", true) || it.name.contains("iv", true) || it.name.contains("ev", true)
        })
    }

    @Test
    fun `battle state rejects exact opponent combat stats at the public boundary`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            state(
                pokemonViews = listOf(
                    pokemon(allyId, BattleSide.ALLY),
                    pokemon(
                        opponentId,
                        BattleSide.OPPONENT,
                        BattleCombatStatRangesView.exact(180, 140, 120, 100, 110, 90),
                    ),
                ),
            )
        }
    }

    private fun state(
        remainingPokemonBySide: Map<BattleSide, Int> = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
        observedEvents: List<BattleObservedEventView> = emptyList(),
        inferences: List<BattleInferenceView> = emptyList(),
        pokemonViews: List<BattlePokemonStateView> = listOf(
            pokemon(allyId, BattleSide.ALLY),
            pokemon(opponentId, BattleSide.OPPONENT),
        ),
    ): BattleStateView = BattleStateView(
        battleId = UUID.fromString("30000000-0000-0000-0000-000000000003"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = pokemonViews,
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = remainingPokemonBySide,
        observedEvents = observedEvents,
        inferences = inferences,
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        combatStats: BattleCombatStatRangesView? = null,
    ): BattlePokemonStateView = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "test_species",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        combatStats = combatStats,
    )
}
