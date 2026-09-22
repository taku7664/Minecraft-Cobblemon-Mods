package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalSpecialTurnOrderTest {
    @Test
    fun `an active trick room is a reversible toggle not a redundant refresh`() {
        val trickRoom = move(
            "trickroom",
            "psychic",
            BattleMoveDamageCategory.STATUS,
            priority = -7,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.FIELD_CONDITION,
                        target = BattleMoveEffectTarget.FIELD,
                        valueId = "trickroom",
                    ),
                ),
                scriptedBehavior = false,
            ),
        )
        val context = context(state(room = "trickroom"), trickRoom)

        assertEquals(
            0.0,
            LocalTacticalSituationalEvaluator.activePersistentEffectRefreshPenalty(trickRoom, context),
            1e-9,
        )
    }

    @Test
    fun `gale wings applies only while the user is at full health`() {
        val braveBird = move("bravebird", "flying", BattleMoveDamageCategory.PHYSICAL)

        assertEquals(1, LocalPublicTurnOrder.effectivePriority(state(allyAbility = "galewings"), BattleSide.ALLY, braveBird))
        assertEquals(
            0,
            LocalPublicTurnOrder.effectivePriority(
                state(allyAbility = "galewings", allyHp = 0.99),
                BattleSide.ALLY,
                braveBird,
            ),
        )
    }

    @Test
    fun `triage and grassy glide expose their public priority`() {
        val drainingKiss = move(
            "drainingkiss",
            "fairy",
            BattleMoveDamageCategory.SPECIAL,
            effects = effects(BattleMoveEffectKind.DRAIN_FRACTION),
        )
        val grassyGlide = move("grassyglide", "grass", BattleMoveDamageCategory.PHYSICAL)

        assertEquals(
            3,
            LocalPublicTurnOrder.effectivePriority(
                state(allyAbility = "triage"),
                BattleSide.ALLY,
                drainingKiss,
            ),
        )
        assertEquals(
            1,
            LocalPublicTurnOrder.effectivePriority(
                state(terrain = "grassyterrain"),
                BattleSide.ALLY,
                grassyGlide,
            ),
        )
        val strengthSap = move(
            "strengthsap",
            "grass",
            BattleMoveDamageCategory.STATUS,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                emptyList(),
                scriptedBehavior = true,
                mechanicFlags = setOf("heal"),
            ),
        )
        assertEquals(
            3,
            LocalPublicTurnOrder.effectivePriority(
                state(allyAbility = "triage"),
                BattleSide.ALLY,
                strengthSap,
            ),
        )
    }

    @Test
    fun `quick draw and quick claw are probabilities while mycelium might is always late`() {
        val attack = move("psychic", "psychic", BattleMoveDamageCategory.SPECIAL)
        val status = move(
            "spore",
            "grass",
            BattleMoveDamageCategory.STATUS,
            effects = statusEffects("slp"),
        )

        assertEquals(
            0.30,
            LocalPublicTurnOrder.fractionalPriorityChance(
                state(allyAbility = "quickdraw"), BattleSide.ALLY, attack,
            ),
            1e-9,
        )
        assertEquals(
            0.20,
            LocalPublicTurnOrder.fractionalPriorityChance(
                state(allyItem = "quickclaw"), BattleSide.ALLY, attack,
            ),
            1e-9,
        )
        assertTrue(
            LocalPublicTurnOrder.alwaysLastWithinPriority(
                state(allyAbility = "myceliummight"), BattleSide.ALLY, status,
            ),
        )
        val facts = PublicBattleTacticalCalculator.calculate(
            context(state(allyAbility = "quickdraw"), attack),
        ).candidates.single().facts
        assertEquals(0.30, facts?.actsFirstProbability ?: -1.0, 1e-9)
        assertEquals(
            0.30,
            LocalPublicTurnOrder.actsFirstProbability(
                state = state(allyAbility = "quickdraw"),
                firstSide = BattleSide.ALLY,
                firstAction = attack,
                secondSide = BattleSide.OPPONENT,
                secondAction = attack,
            ) ?: -1.0,
            1e-9,
        )
        assertTrue(
            LocalPublicMechanicsKernel.projectMove(
                status,
                context(state(opponentAbility = "insomnia"), status),
            ).publiclyNullified,
        )
        assertFalse(
            LocalPublicMechanicsKernel.projectMove(
                status,
                context(state(allyAbility = "myceliummight", opponentAbility = "insomnia"), status),
            ).publiclyNullified,
            "Mycelium Might status moves ignore ability-based status immunity.",
        )
    }

    @Test
    fun `quick claw can override stall inside and outside trick room`() {
        val attack = move("tackle", "normal", BattleMoveDamageCategory.PHYSICAL)
        listOf(null, "trickroom").forEach { room ->
            val battle = state(allyAbility = "stall", allyItem = "quickclaw", room = room)

            assertEquals(
                0.20,
                LocalPublicTurnOrder.fractionalPriorityChance(battle, BattleSide.ALLY, attack),
                1e-9,
            )
            assertEquals(
                0.20,
                LocalPublicTurnOrder.actsFirstProbability(
                    battle,
                    BattleSide.ALLY,
                    attack,
                    BattleSide.OPPONENT,
                    attack,
                ) ?: -1.0,
                1e-9,
            )
        }
    }

    @Test
    fun `quick draw can override a lagging item but quick claw cannot override mycelium might`() {
        val attack = move("tackle", "normal", BattleMoveDamageCategory.PHYSICAL)
        val status = move(
            "spore",
            "grass",
            BattleMoveDamageCategory.STATUS,
            effects = statusEffects("slp"),
        )
        val laggingDraw = state(allyAbility = "quickdraw", allyItem = "laggingtail")
        val myceliumClaw = state(allyAbility = "myceliummight", allyItem = "quickclaw")

        assertEquals(
            0.30,
            LocalPublicTurnOrder.actsFirstProbability(
                laggingDraw,
                BattleSide.ALLY,
                attack,
                BattleSide.OPPONENT,
                attack,
            ) ?: -1.0,
            1e-9,
        )
        assertEquals(
            0.0,
            LocalPublicTurnOrder.fractionalPriorityChance(myceliumClaw, BattleSide.ALLY, status),
            1e-9,
        )
        assertEquals(
            0.0,
            LocalPublicTurnOrder.actsFirstProbability(
                myceliumClaw,
                BattleSide.ALLY,
                status,
                BattleSide.OPPONENT,
                status,
            ) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `armor tail blocks priority but not ordinary attacks`() {
        val quickAttack = move("quickattack", "normal", BattleMoveDamageCategory.PHYSICAL, priority = 1)
        val tackle = move("tackle", "normal", BattleMoveDamageCategory.PHYSICAL)
        val context = context(state(opponentAbility = "armortail"), quickAttack)

        assertTrue(LocalPublicMechanicsKernel.projectMove(quickAttack, context).publiclyNullified)
        assertFalse(LocalPublicMechanicsKernel.projectMove(tackle, context(tackle)).publiclyNullified)
        val myceliumStatus = move("spore", "grass", BattleMoveDamageCategory.STATUS, priority = 1)
        assertFalse(
            LocalPublicMechanicsKernel.projectMove(
                myceliumStatus,
                context(state(allyAbility = "myceliummight", opponentAbility = "armortail"), myceliumStatus),
            ).publiclyNullified,
            "Mycelium Might status moves ignore target-side ability blockers.",
        )
    }

    @Test
    fun `psychic terrain blocks priority only against a grounded target`() {
        val quickAttack = move("quickattack", "normal", BattleMoveDamageCategory.PHYSICAL, priority = 1)
        assertTrue(
            LocalPublicMechanicsKernel.projectMove(
                quickAttack,
                context(state(terrain = "psychicterrain"), quickAttack),
            ).publiclyNullified,
        )
        assertFalse(
            LocalPublicMechanicsKernel.projectMove(
                quickAttack,
                context(state(terrain = "psychicterrain", opponentTypes = setOf("flying")), quickAttack),
            ).publiclyNullified,
        )
        val pranksterField = move(
            "tailwind",
            "flying",
            BattleMoveDamageCategory.STATUS,
            targetPattern = BattleMoveTargetPattern.SIDE,
            explicitOpponentTarget = false,
        )
        assertFalse(
            LocalPublicMechanicsKernel.projectMove(
                pranksterField,
                context(state(allyAbility = "prankster", terrain = "psychicterrain"), pranksterField),
            ).publiclyNullified,
            "A field action must not be mistaken for an attack on the grounded opponent.",
        )
    }

    @Test
    fun `prankster status moves fail against opposing dark types`() {
        val thunderWave = move(
            "thunderwave", "electric", BattleMoveDamageCategory.STATUS,
            effects = statusEffects("par"),
        )

        assertTrue(
            LocalPublicMechanicsKernel.projectMove(
                thunderWave,
                context(state(allyAbility = "prankster", opponentTypes = setOf("dark")), thunderWave),
            ).publiclyNullified,
        )
        assertEquals(
            null,
            PublicBattleTacticalCalculator.calculate(
                context(state(allyAbility = "prankster", opponentTypes = setOf("dark")), thunderWave),
            ).candidates.single().facts?.statusEffectProbability,
        )
        assertFalse(
            LocalPublicMechanicsKernel.projectMove(
                thunderWave,
                context(state(opponentTypes = setOf("dark")), thunderWave),
            ).publiclyNullified,
        )
    }

    @Test
    fun `powder moves respect grass overcoat and safety goggles immunity`() {
        val sleepPowder = move(
            "sleeppowder", "grass", BattleMoveDamageCategory.STATUS,
            effects = statusEffects("slp", setOf("powder")),
        )

        assertTrue(LocalPublicMechanicsKernel.projectMove(
            sleepPowder, context(state(opponentTypes = setOf("grass")), sleepPowder),
        ).publiclyNullified)
        assertEquals(
            null,
            PublicBattleTacticalCalculator.calculate(
                context(state(opponentTypes = setOf("grass")), sleepPowder),
            ).candidates.single().facts?.statusEffectProbability,
        )
        assertTrue(LocalPublicMechanicsKernel.projectMove(
            sleepPowder, context(state(opponentAbility = "overcoat"), sleepPowder),
        ).publiclyNullified)
        assertTrue(LocalPublicMechanicsKernel.projectMove(
            sleepPowder, context(state(opponentItem = "safetygoggles"), sleepPowder),
        ).publiclyNullified)
        assertFalse(LocalPublicMechanicsKernel.projectMove(
            sleepPowder,
            context(state(opponentItem = "safetygoggles", room = "magicroom"), sleepPowder),
        ).publiclyNullified)
    }

    @Test
    fun `root score uses effective rather than template priority`() {
        val braveBird = move("bravebird", "flying", BattleMoveDamageCategory.PHYSICAL)
        val ordinary = LocalTacticalScorer.score(braveBird, context(state(), braveBird))
        val galeWings = LocalTacticalScorer.score(
            braveBird,
            context(state(allyAbility = "galewings"), braveBird),
        )

        assertTrue(galeWings > ordinary)
    }

    @Test
    fun `weather speed abilities reverse again under trick room and respect weather suppression`() {
        val attack = move("surf", "water", BattleMoveDamageCategory.SPECIAL)
        val rain = state(allyAbility = "swiftswim", weather = "raindance")

        assertEquals(1.0, LocalPublicTurnOrder.actsFirstProbability(
            rain, BattleSide.ALLY, attack, BattleSide.OPPONENT, attack,
        ))
        assertEquals(0.0, LocalPublicTurnOrder.actsFirstProbability(
            state(allyAbility = "swiftswim", weather = "raindance", room = "trickroom"),
            BattleSide.ALLY, attack, BattleSide.OPPONENT, attack,
        ))
        assertEquals(0.0, LocalPublicTurnOrder.actsFirstProbability(
            state(allyAbility = "swiftswim", opponentAbility = "cloudnine", weather = "raindance"),
            BattleSide.ALLY, attack, BattleSide.OPPONENT, attack,
        ))
        listOf(
            Triple("chlorophyll", "sunnyday", null),
            Triple("sandrush", "sandstorm", null),
            Triple("slushrush", "snow", null),
            Triple("surgesurfer", null, "electricterrain"),
        ).forEach { (ability, weather, terrain) ->
            val fieldState = state(allyAbility = ability, weather = weather, terrain = terrain)
            val ally = fieldState.pokemon.first { it.side == BattleSide.ALLY }
            assertEquals(160 to 160, LocalPublicTurnOrder.effectiveSpeed(fieldState, ally), ability)
        }
    }

    @Test
    fun `magic room suppresses item based order and grounding`() {
        val attack = move("tackle", "normal", BattleMoveDamageCategory.PHYSICAL)
        val magic = state(allyItem = "quickclaw", room = "magicroom")

        assertEquals(0.0, LocalPublicTurnOrder.fractionalPriorityChance(magic, BattleSide.ALLY, attack))
        assertFalse(LocalPublicTurnOrder.alwaysLastWithinPriority(
            state(allyItem = "laggingtail", room = "magicroom"), BattleSide.ALLY, attack,
        ))
        val balloon = state(allyItem = "airballoon")
        assertFalse(LocalPublicTurnOrder.grounded(
            balloon, balloon.pokemon.first { it.side == BattleSide.ALLY },
        ))
        val balloonInMagic = state(allyItem = "airballoon", room = "magicroom")
        assertTrue(LocalPublicTurnOrder.grounded(
            balloonInMagic, balloonInMagic.pokemon.first { it.side == BattleSide.ALLY },
        ))
    }

    private fun effects(kind: BattleMoveEffectKind) = BattleMoveEffectsView(
        coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
        effects = listOf(
            BattleMoveEffectView(
                kind = kind,
                target = BattleMoveEffectTarget.USER,
                fractionRange = BattleFractionRange(0.5, 0.5),
            ),
        ),
        scriptedBehavior = false,
    )

    private fun statusEffects(statusId: String, flags: Set<String> = emptySet()) = BattleMoveEffectsView(
        coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
        effects = listOf(
            BattleMoveEffectView(
                kind = BattleMoveEffectKind.STATUS,
                target = BattleMoveEffectTarget.SELECTED_TARGET,
                probability = 1.0,
                valueId = statusId,
            ),
        ),
        scriptedBehavior = false,
        mechanicFlags = flags,
    )

    private fun move(
        id: String,
        type: String,
        category: BattleMoveDamageCategory,
        priority: Int = 0,
        effects: BattleMoveEffectsView? = null,
        targetPattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        explicitOpponentTarget: Boolean = true,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = if (explicitOpponentTarget) listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)) else emptyList(),
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = category,
            power = if (category == BattleMoveDamageCategory.STATUS) 0.0 else 80.0,
            accuracy = 100.0,
            priority = priority,
            currentPp = 10,
            targetPattern = targetPattern,
            effects = effects,
        ),
    )

    private fun state(
        allyAbility: String? = null,
        allyItem: String? = null,
        allyHp: Double = 1.0,
        opponentAbility: String? = null,
        opponentItem: String? = null,
        opponentTypes: Set<String> = setOf("normal"),
        terrain: String? = null,
        room: String? = null,
        weather: String? = null,
    ) = BattleStateView(
        battleId = UUID.randomUUID(),
        format = BattleFormat.SINGLE,
        turn = 2,
        pokemon = listOf(
            mon(BattleSide.ALLY, allyAbility, allyItem, allyHp, setOf("normal"), 80),
            mon(BattleSide.OPPONENT, opponentAbility, opponentItem, 1.0, opponentTypes, 120),
        ),
        field = BattleFieldStateView(
            weather = weather?.let { BattleTimedEffectView(it, 3) },
            terrain = terrain?.let { BattleTimedEffectView(it, 3) },
            roomEffects = room?.let { listOf(BattleTimedEffectView(it, 3)) }.orEmpty(),
            globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() },
        ),
        remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun mon(
        side: BattleSide,
        ability: String?,
        item: String?,
        hp: Double,
        types: Set<String>,
        speed: Int,
    ) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:probe",
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = item,
        fainted = false,
        knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, speed)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(150, 180),
                BattleIntegerRange(90, 120),
                BattleIntegerRange(90, 120),
                BattleIntegerRange(90, 120),
                BattleIntegerRange(90, 120),
                BattleIntegerRange(speed, speed + 20),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun context(state: BattleStateView, candidate: BattleActionCandidate) = BattleDecisionContext(
        requestId = UUID.randomUUID(),
        state = state,
        candidates = listOf(candidate),
        deadlineEpochMillis = Long.MAX_VALUE,
        memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView.empty(),
    )

    private fun context(candidate: BattleActionCandidate) = context(state(opponentAbility = "armortail"), candidate)
}
