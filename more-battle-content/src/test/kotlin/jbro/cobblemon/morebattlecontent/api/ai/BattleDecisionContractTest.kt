package jbro.cobblemon.morebattlecontent.api.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BattleDecisionContractTest {
    @Test
    fun `decision context carries only opaque public opponent preview slots`() {
        val preview = BattleOpponentTeamPreviewView(
            selectionSize = 3,
            pokemon = listOf(
                previewPokemon(0, "cobblemon:fluttermane"),
                previewPokemon(1, "cobblemon:urshifu"),
                previewPokemon(2, "cobblemon:rillaboom"),
            ),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = context(UUID.randomUUID(), 10_000L).state,
            candidates = listOf(candidate()),
            deadlineEpochMillis = 10_000L,
            opponentTeamPreview = preview,
        )

        assertSame(preview, context.opponentTeamPreview)
        assertEquals(0, preview.pokemon.first().previewSlotId)
        assertNull(
            preview.pokemon.first()::class.java.declaredFields.singleOrNull {
                it.name.contains("battlePokemon", ignoreCase = true)
            },
            "A public preview slot must not expose the live BattlePokemon identity",
        )
    }

    @Test
    fun `opponent preview rejects invalid selection and hidden exact stats`() {
        assertThrows(IllegalArgumentException::class.java) {
            BattleOpponentTeamPreviewView(
                selectionSize = 2,
                pokemon = listOf(previewPokemon(0, "cobblemon:mew")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            previewPokemon(6, "cobblemon:mew")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleOpponentTeamPreviewView(
                selectionSize = 1,
                pokemon = listOf(
                    previewPokemon(0, "cobblemon:mew"),
                    previewPokemon(0, "cobblemon:ditto"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = 0,
                speciesId = "cobblemon:mew",
                formId = "normal",
                level = 50,
                knownTypeIds = setOf("psychic"),
                combatStats = BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, 100),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = 0,
                speciesId = "cobblemon:mew",
                formId = "normal",
                level = 50,
                knownTypeIds = setOf("psychic"),
                combatStats = BattleCombatStatRangesView(
                    maxHp = BattleIntegerRange(100, 100),
                    attack = BattleIntegerRange(100, 100),
                    defence = BattleIntegerRange(100, 100),
                    specialAttack = BattleIntegerRange(100, 100),
                    specialDefence = BattleIntegerRange(100, 100),
                    speed = BattleIntegerRange(100, 100),
                    knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
                ),
            )
        }
    }

    @Test
    fun `opponent preview identity is an opaque numeric slot rather than caller text`() {
        val getter = BattleOpponentTeamPreviewPokemonView::class.java.getDeclaredMethod("getPreviewSlotId")

        assertEquals(Int::class.javaPrimitiveType, getter.returnType)
    }

    @Test
    fun `decision context retains the old Kotlin default constructor ABI`() {
        val constructors = BattleDecisionContext::class.java.declaredConstructors

        assertTrue(constructors.any { constructor ->
            constructor.parameterCount == 8 &&
                constructor.parameterTypes[6] == Int::class.javaPrimitiveType &&
                constructor.parameterTypes[7].name == "kotlin.jvm.internal.DefaultConstructorMarker"
        })
    }

    @Test
    fun `derived decision context preserves public opponent preview by default`() {
        val preview = BattleOpponentTeamPreviewView(
            selectionSize = 3,
            pokemon = (0 until 6).map { slot -> previewPokemon(slot, "cobblemon:species$slot") },
        )
        val original = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = context(UUID.randomUUID(), 10_000L).state,
            candidates = listOf(candidate()),
            deadlineEpochMillis = 10_000L,
            opponentTeamPreview = preview,
        )

        val derived = original.copy(deadlineEpochMillis = 20_000L)

        assertSame(preview, derived.opponentTeamPreview)
        assertEquals(20_000L, derived.deadlineEpochMillis)
        assertSame(original.state, derived.state)
        assertSame(original.memory, derived.memory)
        assertSame(original.publicActionCatalog, derived.publicActionCatalog)
    }

    @Test
    fun `decision deadline allows a fifteen second router budget`() {
        assertEquals(20_000L, BattleBrainDefaults.DECISION_TIMEOUT_MILLIS)
    }

    @Test
    fun `decision must match the current request candidate and deadline`() {
        val requestId = UUID.randomUUID()
        val now = 1_000L
        val context = context(requestId, now + BattleBrainDefaults.DECISION_TIMEOUT_MILLIS)

        assertEquals(BattleDecisionValidationStatus.VALID, validate(context, decision(requestId), now))
        assertEquals(
            BattleDecisionValidationStatus.STALE_REQUEST,
            validate(context, decision(UUID.randomUUID()), now),
        )
        assertEquals(
            BattleDecisionValidationStatus.UNKNOWN_ACTION,
            validate(context, BattleDecision(requestId, "invented"), now),
        )
        assertEquals(
            BattleDecisionValidationStatus.DEADLINE_EXPIRED,
            validate(context, decision(requestId), context.deadlineEpochMillis + 1),
        )
    }

    @Test
    fun `decision contexts reject duplicate server action ids`() {
        val candidate = candidate()
        assertThrows(IllegalArgumentException::class.java) {
            context(UUID.randomUUID(), 10_000L, listOf(candidate, candidate))
        }
    }

    @Test
    fun `action candidates reject fields that contradict their kind`() {
        assertThrows(IllegalArgumentException::class.java) {
            BattleActionCandidate(
                actionId = "invalid:move-switch",
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                switchPokemonId = UUID.randomUUID(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleActionCandidate(
                actionId = "invalid:duplicate-composite",
                kind = BattleActionKind.COMPOSITE,
                componentActionIds = listOf("move:0", "move:0"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleActionCandidate(
                actionId = "invalid:wait-target",
                kind = BattleActionKind.WAIT,
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            )
        }
    }

    @Test
    fun `public AI contract does not expose Cobblemon implementation classes`() {
        val publicTypes = listOf(
            BattleBrainOpenContext::class.java,
            BattleDecisionContext::class.java,
            BattleStateView::class.java,
            BattleActionCandidate::class.java,
        )

        val exposedTypes = publicTypes.flatMap { type ->
            type.declaredFields.map { it.genericType.typeName }
        }
        assertFalse(exposedTypes.any { it.startsWith("com.cobblemon.") })
    }

    private fun context(
        requestId: UUID,
        deadline: Long,
        candidates: List<BattleActionCandidate> = listOf(candidate()),
    ) = BattleDecisionContext(
        requestId = requestId,
        state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = emptyList(),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        ),
        candidates = candidates,
        deadlineEpochMillis = deadline,
    )

    private fun candidate() = BattleActionCandidate(
        actionId = "move:0",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
    )

    private fun previewPokemon(slot: Int, species: String) = BattleOpponentTeamPreviewPokemonView(
        previewSlotId = slot,
        speciesId = species,
        formId = "normal",
        level = 50,
        knownTypeIds = setOf("ghost"),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(100, 150),
            attack = BattleIntegerRange(80, 120),
            defence = BattleIntegerRange(80, 120),
            specialAttack = BattleIntegerRange(100, 180),
            specialDefence = BattleIntegerRange(100, 180),
            speed = BattleIntegerRange(100, 180),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
    )

    private fun decision(requestId: UUID) = BattleDecision(requestId, "move:0")

    private fun validate(
        context: BattleDecisionContext,
        decision: BattleDecision,
        now: Long,
    ) = BattleDecisionValidator.validate(context, decision, now)
}
