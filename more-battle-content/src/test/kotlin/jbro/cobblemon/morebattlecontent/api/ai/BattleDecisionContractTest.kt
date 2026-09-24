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
    fun `opponent preview carries an immutable public move pool without a live pokemon identity`() {
        val moveIds = linkedSetOf("moonblast", "shadowball")
        val moveDetails = linkedMapOf(
            "moonblast" to BattleMoveCandidateView(
                "fairy",
                BattleMoveDamageCategory.SPECIAL,
                95.0,
                100.0,
                0,
                24,
            ),
        )
        val pool = BattleOpponentPreviewMovePoolView(
            speciesId = "cobblemon:fluttermane",
            formId = "normal",
            moveIds = moveIds,
            sourceId = "fixture:public-learnset",
            moveDetails = moveDetails,
        )
        val preview = BattleOpponentTeamPreviewPokemonView(
            previewSlotId = 0,
            speciesId = "cobblemon:fluttermane",
            formId = "normal",
            level = 50,
            knownTypeIds = setOf("ghost", "fairy"),
            moveCandidatePool = pool,
        )

        moveIds.clear()
        moveDetails.clear()

        assertSame(pool, preview.moveCandidatePool)
        assertEquals(setOf("moonblast", "shadowball"), pool.moveIds)
        assertEquals(setOf("moonblast"), pool.moveDetails.keys)
        assertNull(pool::class.java.declaredFields.singleOrNull {
            it.name.contains("battlePokemon", ignoreCase = true)
        })
        assertThrows(IllegalArgumentException::class.java) {
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = 0,
                speciesId = "cobblemon:mimikyu",
                formId = "normal",
                level = 50,
                moveCandidatePool = pool,
            )
        }
    }

    @Test
    fun `opponent preview carries immutable legal ability and gender priors for the same public form`() {
        val abilities = mutableListOf(
            BattleOpponentPreviewAbilityView("multiscale", BattleAbilityAvailability.HIDDEN),
            BattleOpponentPreviewAbilityView("innerfocus", BattleAbilityAvailability.REGULAR),
        )
        val genders = linkedMapOf("M" to 0.5, "F" to 0.5)
        val pool = BattleOpponentPreviewBuildPoolView(
            speciesId = "cobblemon:dragonite",
            formId = "normal",
            abilities = abilities,
            genderRates = genders,
            sourceId = "fixture:public-form",
        )
        val preview = BattleOpponentTeamPreviewPokemonView(
            previewSlotId = 0,
            speciesId = "cobblemon:dragonite",
            formId = "normal",
            level = 50,
            buildCandidatePool = pool,
        )

        abilities.clear()
        genders.clear()

        assertSame(pool, preview.buildCandidatePool)
        assertEquals(setOf("multiscale", "innerfocus"), pool.abilities.mapTo(linkedSetOf()) { it.abilityId })
        assertEquals(mapOf("M" to 0.5, "F" to 0.5), pool.genderRates)
        assertThrows(IllegalArgumentException::class.java) {
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = 0,
                speciesId = "cobblemon:mimikyu",
                formId = "normal",
                level = 50,
                buildCandidatePool = pool,
            )
        }
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
    fun `opponent preview retains its old Kotlin default constructor ABI`() {
        val constructors = BattleOpponentTeamPreviewPokemonView::class.java.declaredConstructors

        assertTrue(constructors.any { constructor ->
            constructor.parameterCount == 9 &&
                constructor.parameterTypes[7] == Int::class.javaPrimitiveType &&
                constructor.parameterTypes[8].name == "kotlin.jvm.internal.DefaultConstructorMarker"
        })
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
    fun `decision context preserves a complete exact own team without admitting opponent builds`() {
        val allyId = UUID.randomUUID()
        val opponentId = UUID.randomUUID()
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(allyId, BattleSide.ALLY),
                pokemon(opponentId, BattleSide.OPPONENT),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val ownTeam = BattleExactOwnTeamView(listOf(exactBuild(allyId)))
        val original = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state,
            candidates = listOf(candidate()),
            deadlineEpochMillis = 10_000L,
        ).copy(exactOwnTeam = ownTeam)

        val derived = original.copy(deadlineEpochMillis = 20_000L)

        assertSame(ownTeam, derived.exactOwnTeam)
        assertSame(ownTeam.buildFor(allyId), derived.exactOwnTeam?.buildFor(allyId))
        assertNull(derived.exactOwnTeam?.buildFor(opponentId))
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(exactOwnTeam = BattleExactOwnTeamView(listOf(exactBuild(opponentId))))
        }
    }

    @Test
    fun `exact own build rejects incomplete or invented numeric spreads`() {
        val id = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            BattleExactPokemonBuildView(
                battlePokemonId = id,
                abilityId = "protosynthesis",
                heldItemId = null,
                natureId = "timid",
                gender = "N",
                evs = exactSpread().minus("spe"),
                ivs = exactSpread(31),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleExactPokemonBuildView(
                battlePokemonId = id,
                abilityId = "protosynthesis",
                heldItemId = null,
                natureId = "timid",
                gender = "N",
                evs = exactSpread(252),
                ivs = exactSpread(31),
            )
        }
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

    private fun pokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = if (side == BattleSide.ALLY) "cobblemon:pikachu" else "cobblemon:fluttermane",
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownVolatileEffectIds = emptySet(),
    )

    private fun exactBuild(id: UUID) = BattleExactPokemonBuildView(
        battlePokemonId = id,
        abilityId = "static",
        heldItemId = "lightball",
        natureId = "timid",
        gender = "M",
        evs = mapOf("hp" to 4, "atk" to 0, "def" to 0, "spa" to 252, "spd" to 0, "spe" to 252),
        ivs = exactSpread(31),
    )

    private fun exactSpread(value: Int = 0) = mapOf(
        "hp" to value,
        "atk" to value,
        "def" to value,
        "spa" to value,
        "spd" to value,
        "spe" to value,
    )

    private fun decision(requestId: UUID) = BattleDecision(requestId, "move:0")

    private fun validate(
        context: BattleDecisionContext,
        decision: BattleDecision,
        now: Long,
    ) = BattleDecisionValidator.validate(context, decision, now)
}
