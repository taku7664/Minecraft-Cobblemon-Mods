package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.calculation.LocalForcedReplacementResolver
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.LocalTurnBranchPruner
import jbro.cobblemon.morebattlecontent.betterai.state.LocalRecursiveMoveHabit
import jbro.cobblemon.morebattlecontent.betterai.state.LocalRecursiveSwitchTempo
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveSnapshotActionConstraints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalRecursiveLookaheadTest {
    @Test
    fun `recursive recovery habit uses root memory and keeps low hp survival recovery`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.89, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val recovery = BattleActionCandidate(
            actionId = "recover",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:recover",
            moveDetails = BattleMoveCandidateView(
                typeId = "normal",
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 5,
                targetPattern = BattleMoveTargetPattern.SELF,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = listOf(
                        BattleMoveEffectView(
                            BattleMoveEffectKind.HEAL_FRACTION,
                            BattleMoveEffectTarget.USER,
                            fractionRange = BattleFractionRange(0.5, 0.5),
                        ),
                    ),
                    scriptedBehavior = true,
                ),
            ),
        )
        val history = RecursiveSnapshotActionConstraints.seed(
            initial,
            allyLastMoveId = "cobblemon:recover",
            allySameMoveRepeatCount = 2,
        )

        assertEquals(2, history.moveStreakByPokemon.getValue(ALLY_ID).count)
        assertTrue(LocalRecursiveMoveHabit.cost(initial, BattleSide.ALLY, recovery, history) > 0.0)
        val lowHp = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.25, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        assertEquals(0.0, LocalRecursiveMoveHabit.cost(lowHp, BattleSide.ALLY, recovery, history), 1e-9)
    }

    @Test
    fun `recursive switch tempo remembers the immediately previous action`() {
        assertEquals(-0.15, LocalRecursiveSwitchTempo.adjustment(allySwitch = true, opponentSwitch = false, allyRepeated = false, opponentRepeated = false), 1e-9)
        assertEquals(-0.45, LocalRecursiveSwitchTempo.adjustment(allySwitch = true, opponentSwitch = false, allyRepeated = true, opponentRepeated = false), 1e-9)
        assertEquals(0.45, LocalRecursiveSwitchTempo.adjustment(allySwitch = false, opponentSwitch = true, allyRepeated = false, opponentRepeated = true), 1e-9)
        assertEquals(0.0, LocalRecursiveSwitchTempo.adjustment(allySwitch = true, opponentSwitch = true, allyRepeated = true, opponentRepeated = true), 1e-9)
    }

    @Test
    fun `faster public knockout cancels the slower move`() {
        val state = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.10, speed = 50),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 150)),
        )
        val context = context(state, listOf(move("ally_hit", 0, power = 120.0)))

        val result = PublicSingleTurnProjector.project(
            state,
            move("ally_hit", 0, power = 120.0),
            move("opponent_ko", 0, power = 200.0, targetSide = BattleSide.ALLY),
            context,
        ).single()

        assertEquals(listOf(BattleSide.OPPONENT, BattleSide.ALLY), result.order)
        assertEquals(0.0, result.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction)
        assertEquals(1.0, result.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction)
    }

    @Test
    fun `confirmed same-priority action order resolves an otherwise overlapping speed range`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.10, speed = 100),
            opponents = listOf(
                pokemon(
                    OPPONENT_ID,
                    BattleSide.OPPONENT,
                    0,
                    1.0,
                    speed = 100,
                    speedRange = 50..150,
                ),
            ),
            inferences = listOf(
                BattleInferenceView(
                    subjectPokemonId = OPPONENT_ID,
                    categoryId = "observed_action_order",
                    candidateId = "BEFORE_AT_SAME_BASE_PRIORITY",
                    confidence = BattleInferenceConfidence.CONFIRMED,
                    basis = setOf(BattleInferenceBasis.ACTION_ORDER),
                    evidenceEventSequences = listOf(1L, 2L),
                    relatedPokemonId = ALLY_ID,
                ),
            ),
        )
        val allyMove = move("ally_hit", 0, power = 120.0)

        val result = PublicSingleTurnProjector.project(
            initial,
            allyMove,
            move("observed_faster_ko", 0, power = 200.0, targetSide = BattleSide.ALLY),
            context(initial, listOf(allyMove)),
        ).single()

        assertEquals(listOf(BattleSide.OPPONENT, BattleSide.ALLY), result.order)
        assertEquals(0.0, result.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction)
        assertEquals(1.0, result.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction)
    }

    @Test
    fun `trick room reverses known speed order but not move priority`() {
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.1, speed = 150),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 50),
            ),
            field = BattleFieldStateView(
                weather = null,
                terrain = null,
                roomEffects = listOf(BattleTimedEffectView("cobblemon:trick_room", 3)),
                globalEffects = emptyList(),
                sideConditions = BattleSide.entries.associateWith { emptyList() },
            ),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )

        val result = PublicSingleTurnProjector.project(
            initial,
            move("fast", 0, power = 200.0),
            move("slow", 0, power = 200.0, targetSide = BattleSide.ALLY),
            context(initial, listOf(move("fast", 0, power = 200.0))),
        ).single()

        assertEquals(listOf(BattleSide.OPPONENT, BattleSide.ALLY), result.order)
        assertEquals(0.0, result.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction)
        assertEquals(1.0, result.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction)
    }

    @Test
    fun `declarative weather move changes the projected next-turn field`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val rainDance = BattleActionCandidate(
            actionId = "rain_dance",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:raindance",
            moveDetails = BattleMoveCandidateView(
                typeId = "water",
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 5,
                targetPattern = BattleMoveTargetPattern.ALL_ACTIVE,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = listOf(
                        BattleMoveEffectView(
                            kind = BattleMoveEffectKind.WEATHER,
                            target = BattleMoveEffectTarget.FIELD,
                            probability = 1.0,
                            valueId = "raindance",
                        ),
                    ),
                    scriptedBehavior = false,
                ),
            ),
        )

        val result = PublicSingleTurnProjector.project(
            initial,
            rainDance,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, listOf(rainDance)),
        ).single()

        assertEquals(4, result.state.field.weather?.remainingTurns)
        assertEquals("raindance", result.state.field.weather?.effectId)
    }

    @Test
    fun `charge move deals no first-turn damage and resolves on its forced continuation`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val chargeMove = move("solar_beam", 0, power = 120.0).let { base ->
            BattleActionCandidate(
                actionId = base.actionId,
                kind = base.kind,
                actorSlot = base.actorSlot,
                moveSlot = base.moveSlot,
                moveId = base.moveId,
                targets = base.targets,
                moveDetails = BattleMoveCandidateView(
                    typeId = "grass",
                    damageCategory = BattleMoveDamageCategory.SPECIAL,
                    power = 120.0,
                    accuracy = 100.0,
                    priority = 0,
                    currentPp = 10,
                    targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
                    effects = BattleMoveEffectsView(
                        coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        effects = listOf(
                            BattleMoveEffectView(
                                kind = BattleMoveEffectKind.CHARGE_TURN,
                                target = BattleMoveEffectTarget.USER,
                                probability = 1.0,
                            ),
                        ),
                        scriptedBehavior = false,
                    ),
                ),
            )
        }
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)

        val first = PublicSingleTurnProjector.project(initial, chargeMove, wait, context(initial, listOf(chargeMove))).single()
        val history = RecursiveHistoryProjector.project(RecursiveActionHistory(), initial, first, chargeMove, wait)
        val second = PublicSingleTurnProjector.project(first.state, chargeMove, wait, context(first.state, listOf(chargeMove)), history).single()

        assertEquals(1.0, first.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction)
        assertEquals("cobblemon:solar_beam", history.chargingMoveByPokemon[ALLY_ID])
        assertTrue(second.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction < 1.0)
    }

    @Test
    fun `switch resolves before a move and changes type immunity`() {
        val ghostId = UUID.fromString("00000000-0000-0000-0000-000000000013")
        val state = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 100, types = setOf("normal")),
                pokemon(ghostId, BattleSide.OPPONENT, null, 1.0, speed = 100, types = setOf("ghost")),
            ),
        )
        val attack = move("normal_hit", 0, power = 200.0, type = "normal")
        val switch = BattleActionCandidate(
            actionId = "ghost_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = ghostId,
        )

        val result = PublicSingleTurnProjector.project(state, attack, switch, context(state, listOf(attack))).single().state

        assertEquals(null, result.pokemon.single { it.battlePokemonId == OPPONENT_ID }.activeSlot)
        assertEquals(0, result.pokemon.single { it.battlePokemonId == ghostId }.activeSlot)
        assertEquals(1.0, result.pokemon.single { it.battlePokemonId == ghostId }.hpFraction)
    }

    @Test
    fun `difficulty depth means complete turns from one through four`() {
        val ownMove = move("own", 0, power = 20.0)
        val opponentMove = BattlePublicMoveOptionView(
            "cobblemon:opponent",
            moveDetails(power = 20.0),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
        )
        val ownOption = BattlePublicMoveOptionView(
            "cobblemon:own",
            moveDetails(power = 20.0),
            BattlePublicMoveKnowledge.EXACT_OWN,
        )
        val state = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val context = context(
            state,
            listOf(ownMove),
            BattlePublicActionCatalogView(
                listOf(
                    BattlePokemonActionCatalogView(ALLY_ID, listOf(ownOption)),
                    BattlePokemonActionCatalogView(OPPONENT_ID, listOf(opponentMove), moveSetComplete = true),
                ),
            ),
        )
        val rank = rank(ownMove)

        BattleDifficultyProfiles.entries.forEachIndexed { index, difficulty ->
            val result = LocalRecursiveLookaheadEvaluator.evaluate(
                listOf(rank),
                context,
                BattleTrainerProfile.balanced(index.coerceAtMost(5), difficulty),
            )
            assertEquals(index + 1, result.depthCompleted)
            assertFalse(result.truncated)
            assertTrue(result.nodesVisited > 0)
        }
    }

    @Test
    fun `double difficulty depth also means complete turns from one through four`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val opponentPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
                pokemon(opponentPartnerId, BattleSide.OPPONENT, 1, 1.0, speed = 80),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        fun rootMove(actionId: String, actorSlot: Int, targetSlot: Int) = BattleActionCandidate(
            actionId = actionId,
            kind = BattleActionKind.USE_MOVE,
            actorSlot = actorSlot,
            moveSlot = 0,
            moveId = "cobblemon:$actionId",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
            moveDetails = moveDetails(power = 20.0),
        )
        val components = listOf(rootMove("ally_zero", 0, 0), rootMove("ally_one", 1, 1))
        val ownTurn = BattleActionCandidate(
            actionId = "ally_double_turn",
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = components.map(BattleActionCandidate::actionId),
            componentActions = components,
        )
        fun option(moveId: String, knowledge: BattlePublicMoveKnowledge) = BattlePublicMoveOptionView(
            moveId,
            moveDetails(power = 20.0),
            knowledge,
        )
        val publicCatalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(ALLY_ID, listOf(option("cobblemon:ally_zero", BattlePublicMoveKnowledge.EXACT_OWN))),
                BattlePokemonActionCatalogView(allyPartnerId, listOf(option("cobblemon:ally_one", BattlePublicMoveKnowledge.EXACT_OWN))),
                BattlePokemonActionCatalogView(OPPONENT_ID, listOf(option("cobblemon:opponent_zero", BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true),
                BattlePokemonActionCatalogView(opponentPartnerId, listOf(option("cobblemon:opponent_one", BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true),
            ),
        )
        val decisionContext = context(initial, listOf(ownTurn), publicCatalog)

        BattleDifficultyProfiles.entries.forEachIndexed { index, difficulty ->
            val result = LocalRecursiveLookaheadEvaluator.evaluate(
                listOf(rank(ownTurn)),
                decisionContext,
                BattleTrainerProfile.balanced(index.coerceAtMost(5), difficulty),
            )
            assertEquals(index + 1, result.depthCompleted, difficulty.id)
            assertFalse(result.truncated, difficulty.id)
            assertFalse(result.publicResponseIncomplete, difficulty.id)
            assertTrue(result.nodesVisited > 0, difficulty.id)
        }
    }

    @Test
    fun `introductory double search completes one full turn with bounded joint choices`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000051")
        val allyBenchId = UUID.fromString("00000000-0000-0000-0000-000000000052")
        val opponentPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000053")
        val opponentBenchId = UUID.fromString("00000000-0000-0000-0000-000000000054")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(allyBenchId, BattleSide.ALLY, null, 1.0, speed = 70),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
                pokemon(opponentPartnerId, BattleSide.OPPONENT, 1, 1.0, speed = 80),
                pokemon(opponentBenchId, BattleSide.OPPONENT, null, 1.0, speed = 60),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        fun options(prefix: String, knowledge: BattlePublicMoveKnowledge) = listOf(
            BattlePublicMoveOptionView(
                "cobblemon:${prefix}_strong",
                moveDetails(power = 90.0).copy(targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
                knowledge,
            ),
            BattlePublicMoveOptionView(
                "cobblemon:${prefix}_safe",
                moveDetails(power = 60.0).copy(targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
                knowledge,
            ),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(ALLY_ID, options("ally_zero", BattlePublicMoveKnowledge.EXACT_OWN)),
                BattlePokemonActionCatalogView(allyPartnerId, options("ally_one", BattlePublicMoveKnowledge.EXACT_OWN)),
                BattlePokemonActionCatalogView(OPPONENT_ID, options("opponent_zero", BattlePublicMoveKnowledge.PUBLICLY_REVEALED), moveSetComplete = true),
                BattlePokemonActionCatalogView(opponentPartnerId, options("opponent_one", BattlePublicMoveKnowledge.PUBLICLY_REVEALED), moveSetComplete = true),
            ),
        )
        val rootActions = PublicFutureActionFactory.actions(
            initial,
            BattleSide.ALLY,
            catalog,
            candidateLimitPerSlot = BattleDifficultyProfiles.INTRODUCTORY.doubleCandidateLimitPerSlot,
        )
        val rawContext = context(initial, rootActions, catalog)
        val calculated = PublicBattleTacticalCalculator.calculate(rawContext)
        val profile = BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY)
        val ranks = LocalBattleActionPolicy.rank(calculated, null, profile)
        val started = System.nanoTime()

        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranks, calculated, profile)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(1, result.depthCompleted)
        assertFalse(result.truncated)
        assertTrue(elapsedMillis < 1_000L, "introductory double one-turn search took ${elapsedMillis}ms")
    }

    @Test
    fun `protect in doubles only protects the pokemon that used it`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000023")
        val opponentPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000024")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
                pokemon(opponentPartnerId, BattleSide.OPPONENT, 1, 1.0, speed = 80),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val protect = BattleActionCandidate(
            actionId = "protect_slot_zero",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:protect",
            targets = listOf(BattleTargetSlot(BattleSide.ALLY, 0)),
            moveDetails = BattleMoveCandidateView(
                typeId = "normal",
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                accuracy = 100.0,
                priority = 4,
                currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELF,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = listOf(
                        BattleMoveEffectView(
                            kind = BattleMoveEffectKind.PROTECT_USER,
                            target = BattleMoveEffectTarget.USER,
                        ),
                    ),
                    scriptedBehavior = false,
                ),
            ),
        )
        val attackPartner = BattleActionCandidate(
            actionId = "attack_ally_partner",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:attack_ally_partner",
            targets = listOf(BattleTargetSlot(BattleSide.ALLY, 1)),
            moveDetails = moveDetails(power = 100.0),
        )

        val projected = PublicSingleTurnProjector.project(
            initial,
            protect,
            attackPartner,
            context(initial, listOf(protect)),
        ).single().state

        assertEquals(1.0, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
        assertTrue(projected.pokemon.single { it.battlePokemonId == allyPartnerId }.hpFraction < 1.0)
    }

    @Test
    fun `spread move in doubles damages every declared opponent target`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000025")
        val opponentPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000026")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
                pokemon(opponentPartnerId, BattleSide.OPPONENT, 1, 1.0, speed = 80),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val spread = BattleActionCandidate(
            actionId = "spread",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:dazzlinggleam",
            moveDetails = BattleMoveCandidateView(
                typeId = "fairy",
                damageCategory = BattleMoveDamageCategory.SPECIAL,
                power = 80.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 10,
                targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS,
            ),
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                typeChartMultiplier = 1.0,
                standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
                standardDamageFractionRange = BattleDamageFractionRange(0.25, 0.25),
                standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0),
                standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
        )
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)

        val projected = PublicSingleTurnProjector.project(
            initial,
            spread,
            wait,
            context(initial, listOf(spread)),
        ).single().state

        assertTrue(projected.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction < 1.0)
        assertTrue(projected.pokemon.single { it.battlePokemonId == opponentPartnerId }.hpFraction < 1.0)
    }

    @Test
    fun `random opponent move branches across public double targets instead of becoming inert`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000035")
        val opponentPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000036")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
                pokemon(opponentPartnerId, BattleSide.OPPONENT, 1, 1.0, speed = 80),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val randomMove = BattleActionCandidate(
            actionId = "random",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:randomnormal",
            moveDetails = moveDetails(power = 60.0).copy(targetPattern = BattleMoveTargetPattern.RANDOM_OPPONENT),
            facts = damageFacts(0.2),
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            randomMove,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, listOf(randomMove)),
        )

        assertEquals(2, outcomes.size)
        assertEquals(1.0, outcomes.sumOf(PublicTurnProjection::probability), 1e-9)
        assertTrue(outcomes.all { outcome ->
            outcome.state.pokemon.count {
                it.side == BattleSide.OPPONENT && it.hpFraction < 1.0
            } == 1
        })
    }

    @Test
    fun `double future actions preserve selected ally targets and project their effects`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000027")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val supportDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_ALLY,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.STAT_STAGE,
                        target = BattleMoveEffectTarget.SELECTED_TARGET,
                        statStages = mapOf("attack" to 1),
                    ),
                ),
                scriptedBehavior = false,
            ),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:allysupport",
                            supportDetails,
                            BattlePublicMoveKnowledge.EXACT_OWN,
                        ),
                    ),
                ),
            ),
        )

        val support = PublicFutureActionFactory.primitiveActionsForPokemon(
            initial,
            BattleSide.ALLY,
            ALLY_ID,
            catalog,
        ).single { it.kind == BattleActionKind.USE_MOVE }
        assertEquals(listOf(BattleTargetSlot(BattleSide.ALLY, 1)), support.targets)

        val projected = PublicSingleTurnProjector.project(
            initial,
            support,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, listOf(support), catalog),
        ).single().state
        assertEquals(1, projected.pokemon.single { it.battlePokemonId == allyPartnerId }.statStages["attack"])
        assertEquals(null, projected.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["attack"])
    }

    @Test
    fun `pivot move in doubles replaces only its own active slot`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000028")
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000029")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(benchId, BattleSide.ALLY, null, 1.0, speed = 90),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 80),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val pivotDetails = BattleMoveCandidateView(
            typeId = "bug",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 70.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.SWITCH_USER,
                        target = BattleMoveEffectTarget.USER,
                    ),
                ),
                scriptedBehavior = false,
            ),
        )
        val pivot = BattleActionCandidate(
            actionId = "pivot",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:uturn",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = pivotDetails,
            facts = damageFacts(0.2),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(BattlePublicMoveOptionView("cobblemon:uturn", pivotDetails, BattlePublicMoveKnowledge.EXACT_OWN)),
                ),
            ),
        )

        val projected = PublicSingleTurnProjector.project(
            initial,
            pivot,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, listOf(pivot), catalog),
        ).single().state

        assertEquals(null, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.activeSlot)
        assertEquals(0, projected.pokemon.single { it.battlePokemonId == benchId }.activeSlot)
        assertEquals(1, projected.pokemon.single { it.battlePokemonId == allyPartnerId }.activeSlot)
    }

    @Test
    fun `double candidate cap keeps a strong move and a legal switch instead of list prefix`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000031")
        val opponentPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000032")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 110),
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(benchId, BattleSide.ALLY, null, 1.0, speed = 90),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 80),
                pokemon(opponentPartnerId, BattleSide.OPPONENT, 1, 1.0, speed = 70),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 2),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        fun option(id: String, power: Double) = BattlePublicMoveOptionView(
            "cobblemon:$id",
            moveDetails(power = power).copy(targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
            BattlePublicMoveKnowledge.EXACT_OWN,
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(option("weak_one", 20.0), option("weak_two", 30.0), option("weak_three", 40.0), option("strong_last", 200.0)),
                ),
                BattlePokemonActionCatalogView(allyPartnerId, listOf(option("partner", 50.0))),
            ),
        )

        val actions = PublicFutureActionFactory.actions(
            initial,
            BattleSide.ALLY,
            catalog,
            candidateLimitPerSlot = 3,
        )
        val slotZeroActions = actions.flatMap(BattleActionCandidate::componentActions)
            .filter { it.actorSlot == 0 }
            .distinctBy(BattleActionCandidate::actionId)

        assertEquals(3, slotZeroActions.size)
        assertTrue(slotZeroActions.any { it.moveId == "cobblemon:strong_last" })
        assertTrue(slotZeroActions.any { it.kind == BattleActionKind.SWITCH && it.switchPokemonId == benchId })
    }

    @Test
    fun `double forced replacement fills only the missing active slot`() {
        val allyPartnerId = UUID.fromString("00000000-0000-0000-0000-000000000033")
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000034")
        val fainted = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.0, speed = 110, fainted = true)
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                fainted,
                pokemon(allyPartnerId, BattleSide.ALLY, 1, 1.0, speed = 100),
                pokemon(benchId, BattleSide.ALLY, null, 1.0, speed = 90),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 80),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )

        val result = LocalForcedReplacementResolver.resolve(initial, BattleSide.ALLY, context(initial, listOf(move("x", 0, 10.0))))

        assertEquals(1, result.states.size)
        val replaced = result.states.single()
        assertEquals(0, replaced.pokemon.single { it.battlePokemonId == benchId }.activeSlot)
        assertEquals(1, replaced.pokemon.single { it.battlePokemonId == allyPartnerId }.activeSlot)
    }

    @Test
    fun `current turn pruning starts at depth one but keeps knockout continuations`() {
        assertFalse(
            LocalTurnBranchPruner.shouldStopBranch(
                immediateTurnDelta = -2.8,
                depthRemaining = 3,
                newlyLostAllyHpBefore = 1.0,
            ),
            "even a full-HP sacrifice can be the correct route to a safe revenge attacker",
        )
        assertFalse(
            LocalTurnBranchPruner.shouldStopBranch(
                immediateTurnDelta = -2.8,
                depthRemaining = 1,
                newlyLostAllyHpBefore = 1.0,
            ),
            "depth-one pruning must not erase an intentional sacrifice",
        )
        assertFalse(
            LocalTurnBranchPruner.shouldStopBranch(
                immediateTurnDelta = -2.8,
                depthRemaining = 3,
                newlyLostAllyHpBefore = 0.2,
            ),
            "a low-HP intentional sacrifice must also keep its revenge-kill continuation",
        )
        assertTrue(
            LocalTurnBranchPruner.shouldStopBranch(
                immediateTurnDelta = -0.90,
                depthRemaining = 1,
                newlyLostAllyHpBefore = null,
            ),
            "an introductory one-turn branch must stop when that turn alone is hopeless",
        )
        assertTrue(
            LocalTurnBranchPruner.shouldStopBranch(
                immediateTurnDelta = -1.05,
                depthRemaining = 2,
                newlyLostAllyHpBefore = null,
            ),
            "the second simulated turn must use its own immediate delta rather than the accumulated line",
        )
        assertFalse(
            LocalTurnBranchPruner.shouldStopBranch(
                immediateTurnDelta = -0.6,
                depthRemaining = 3,
                newlyLostAllyHpBefore = null,
            ),
            "ordinary bad turns and failed reads are not catastrophic branches",
        )
    }

    @Test
    fun `difficulty budgets cap the whole iterative search`() {
        assertEquals(250L, LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.INTRODUCTORY).timeMillis)
        assertEquals(750L, LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.STANDARD).timeMillis)
        assertEquals(1_500L, LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.ADVANCED).timeMillis)
        assertEquals(3_000L, LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.BOSS).timeMillis)

        assertTrue(
            LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.INTRODUCTORY).chanceBranchesPerMove <
                LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.BOSS).chanceBranchesPerMove,
        )
    }

    @Test
    fun `turn projection reuses exact state action calculations`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val attack = move("cached_hit", 0, power = 70.0)
        val wait = BattleActionCandidate("opponent_wait", BattleActionKind.WAIT)
        val source = context(initial, listOf(attack))
        val cache = LocalProjectedActionCalculationCache()

        PublicSingleTurnProjector.project(initial, attack, wait, source, calculationCache = cache)
        val afterFirstProjection = cache.calculationsPerformed
        PublicSingleTurnProjector.project(initial, attack, wait, source, calculationCache = cache)

        assertEquals(1, afterFirstProjection)
        assertEquals(afterFirstProjection, cache.calculationsPerformed)

        val nextTurn = BattleStateView(
            battleId = initial.battleId,
            format = initial.format,
            turn = initial.turn + 1,
            pokemon = initial.pokemon,
            field = initial.field,
            remainingPokemonBySide = initial.remainingPokemonBySide,
            observedEvents = initial.observedEvents,
            inferences = initial.inferences,
        )
        PublicSingleTurnProjector.project(nextTurn, attack, wait, source, calculationCache = cache)
        assertEquals(afterFirstProjection + 1, cache.calculationsPerformed)
    }

    @Test
    fun `probabilistic effects add weighted score without expanding recursive states`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val effects = listOf("attack", "defense", "special_attack", "special_defense", "speed").map { stat ->
            BattleMoveEffectView(
                kind = BattleMoveEffectKind.STAT_STAGE,
                target = BattleMoveEffectTarget.USER,
                probability = 0.5,
                statStages = mapOf(stat to 1),
            )
        }
        val setup = BattleActionCandidate(
            actionId = "five_coin_setup",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:five_coin_setup",
            moveDetails = BattleMoveCandidateView(
                typeId = "normal",
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELF,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = effects,
                    scriptedBehavior = false,
                ),
            ),
        )
        val outcomes = PublicSingleTurnProjector.project(
            initialState = initial,
            allyAction = setup,
            opponentAction = BattleActionCandidate("opponent_wait", BattleActionKind.WAIT),
            sourceContext = context(initial, listOf(setup)),
            maxChanceBranchesPerMove = 4,
        )

        assertEquals(1, outcomes.size)
        assertTrue(
            outcomes.single().state.pokemon.single { it.battlePokemonId == ALLY_ID }.statStages.isEmpty(),
        )
        assertEquals(0.22, outcomes.single().expectedScoreAdjustment, 1e-9)
    }

    @Test
    fun `introductory six choice search completes one turn promptly`() {
        val allyBenchIds = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000031"),
            UUID.fromString("00000000-0000-0000-0000-000000000032"),
        )
        val opponentBenchIds = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000041"),
            UUID.fromString("00000000-0000-0000-0000-000000000042"),
        )
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
                pokemon(allyBenchIds[0], BattleSide.ALLY, null, 1.0, speed = 80),
                pokemon(allyBenchIds[1], BattleSide.ALLY, null, 1.0, speed = 120),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 105),
                pokemon(opponentBenchIds[0], BattleSide.OPPONENT, null, 1.0, speed = 90),
                pokemon(opponentBenchIds[1], BattleSide.OPPONENT, null, 1.0, speed = 110),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val ownMoves = (1..4).map { slot -> move("own_move_$slot", 0, power = 45.0 + slot * 10.0) }
        val rootActions = ownMoves + allyBenchIds.mapIndexed { index, id ->
            BattleActionCandidate(
                actionId = "ally_switch_$index",
                kind = BattleActionKind.SWITCH,
                actorSlot = 0,
                switchPokemonId = id,
            )
        }
        val ownOptions = ownMoves.map { action ->
            BattlePublicMoveOptionView(
                moveId = requireNotNull(action.moveId),
                details = requireNotNull(action.moveDetails),
                knowledge = BattlePublicMoveKnowledge.EXACT_OWN,
            )
        }
        val opponentOptions = (1..4).map { slot ->
            BattlePublicMoveOptionView(
                moveId = "cobblemon:opponent_move_$slot",
                details = moveDetails(power = 40.0 + slot * 10.0),
                knowledge = BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
            )
        }
        val source = context(
            initial,
            rootActions,
            BattlePublicActionCatalogView(
                listOf(
                    BattlePokemonActionCatalogView(ALLY_ID, ownOptions, moveSetComplete = true),
                    BattlePokemonActionCatalogView(OPPONENT_ID, opponentOptions, moveSetComplete = true),
                ),
            ),
        )

        val profile = BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY)
        val started = System.nanoTime()
        val result = LocalRecursiveLookaheadEvaluator.evaluate(
            rootActions.map(::rank),
            source,
            profile,
        )
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        val brain = LocalTacticalBrain()
        val session = brain.openSession(
            BattleBrainOpenContext(initial.battleId, BattleFormat.SINGLE, trainerProfile = profile),
        )
        val wholeDecisionStarted = System.nanoTime()
        val decision = brain.decide(session, source).toCompletableFuture().join()
        val wholeDecisionMillis = (System.nanoTime() - wholeDecisionStarted) / 1_000_000
        println(
            "INTRODUCTORY_SIX_CHOICE_LOOKAHEAD elapsed_ms=$elapsedMillis whole_decision_ms=$wholeDecisionMillis " +
                "nodes=${result.nodesVisited} pruned=${result.branchesPruned}",
        )

        assertEquals(1, result.depthCompleted)
        assertFalse(result.truncated)
        assertTrue(elapsedMillis < 1_000L, "introductory one-turn search took ${elapsedMillis}ms")
        assertTrue(decision.actionId in rootActions.map(BattleActionCandidate::actionId))
        assertTrue("difficulty_introductory" in decision.tags, decision.tags.toString())
        assertTrue("lookahead_requested_1" in decision.tags, decision.tags.toString())
        assertTrue("lookahead_turns_1" in decision.tags, decision.tags.toString())
        assertTrue(wholeDecisionMillis < 1_000L, "whole introductory decision took ${wholeDecisionMillis}ms")
    }

    @Test
    fun `deep search does not prune a full hp knockout before forced replacement`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val ownMove = move("slow_chip", 0, power = 20.0)
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 50),
                pokemon(benchId, BattleSide.ALLY, null, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 150),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(BattlePublicMoveOptionView(requireNotNull(ownMove.moveId), requireNotNull(ownMove.moveDetails), BattlePublicMoveKnowledge.EXACT_OWN)),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    benchId,
                    listOf(BattlePublicMoveOptionView("cobblemon:bench_hit", moveDetails(power = 80.0), BattlePublicMoveKnowledge.EXACT_OWN)),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(BattlePublicMoveOptionView("cobblemon:fast_ko", moveDetails(power = 200.0), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
                    moveSetComplete = true,
                ),
            ),
        )

        val result = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(ownMove)),
            context(initial, listOf(ownMove), catalog),
            BattleTrainerProfile.boss(),
        )

        assertEquals(4, result.depthCompleted)
        assertEquals(0, result.branchesPruned)
        assertFalse(result.truncated)
    }

    @Test
    fun `two turn search continues through a forced replacement after knockout`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000015")
        val knockout = move("knockout", 0, power = 200.0, type = "normal")
        val currentOpponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 0.01, speed = 50)
        val waterBench = pokemon(benchId, BattleSide.OPPONENT, null, 1.0, speed = 50, types = setOf("water"))
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100, types = setOf("psychic")),
            opponents = listOf(currentOpponent, waterBench),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(
                        BattlePublicMoveOptionView(requireNotNull(knockout.moveId), requireNotNull(knockout.moveDetails), BattlePublicMoveKnowledge.EXACT_OWN),
                    ),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(BattlePublicMoveOptionView("cobblemon:current_hit", moveDetails(power = 20.0), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    benchId,
                    listOf(BattlePublicMoveOptionView("cobblemon:bench_hit", moveDetails(power = 20.0), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
                    moveSetComplete = true,
                ),
            ),
        )
        val result = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(knockout)),
            context(initial, listOf(knockout), catalog),
            BattleTrainerProfile.balanced(3, BattleDifficultyProfiles.STANDARD),
        )

        assertEquals(2, result.depthCompleted)
        assertFalse(result.publicResponseIncomplete)
    }

    @Test
    fun `partially revealed forced replacements keep the search explicitly incomplete`() {
        val knownBenchId = UUID.fromString("00000000-0000-0000-0000-000000000017")
        val knockout = move("knockout", 0, power = 200.0, type = "normal")
        val chip = move("chip", 0, power = 5.0, type = "normal")
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 0.5, speed = 50),
                pokemon(knownBenchId, BattleSide.OPPONENT, null, 1.0, speed = 50),
            ),
            opponentRemaining = 3,
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            requireNotNull(knockout.moveId),
                            requireNotNull(knockout.moveDetails),
                            BattlePublicMoveKnowledge.EXACT_OWN,
                        ),
                        BattlePublicMoveOptionView(
                            requireNotNull(chip.moveId),
                            requireNotNull(chip.moveDetails),
                            BattlePublicMoveKnowledge.EXACT_OWN,
                        ),
                    ),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:current_hit",
                            moveDetails(power = 20.0),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        ),
                    ),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    knownBenchId,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:known_bench_hit",
                            moveDetails(power = 20.0),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        ),
                    ),
                    moveSetComplete = true,
                ),
            ),
        )
        val source = context(initial, listOf(knockout, chip), catalog)
        val postKnockout = PublicSingleTurnProjector.project(
            initial,
            knockout,
            move("current-hit", 0, power = 20.0, targetSide = BattleSide.ALLY),
            source,
        ).single().state
        val replacementResolution = LocalForcedReplacementResolver.resolve(
            postKnockout,
            BattleSide.OPPONENT,
            source,
        )

        assertEquals(2, postKnockout.remainingPokemonBySide.getValue(BattleSide.OPPONENT))
        assertEquals(0.5, replacementResolution.publiclyKnownFraction)

        val forward = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(knockout), rank(chip)),
            source,
            BattleTrainerProfile.balanced(3, BattleDifficultyProfiles.STANDARD),
        )
        val reversed = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(chip), rank(knockout)),
            source,
            BattleTrainerProfile.balanced(3, BattleDifficultyProfiles.STANDARD),
        )
        val forwardScores = forward.ranked.associate { it.outcome.candidate.actionId to it.comparisonValue }
        val reversedScores = reversed.ranked.associate { it.outcome.candidate.actionId to it.comparisonValue }

        assertEquals(2, forward.depthCompleted)
        assertTrue(forward.publicResponseIncomplete)
        assertEquals(forwardScores.keys, reversedScores.keys)
        forwardScores.forEach { (actionId, score) ->
            assertEquals(score, reversedScores.getValue(actionId), 1e-9, actionId)
        }
    }

    @Test
    fun `forced replacement resolver promotes each publicly known living bench after knockout`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000016")
        val knockout = move("knockout", 0, power = 200.0)
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 0.05, speed = 50),
                pokemon(benchId, BattleSide.OPPONENT, null, 1.0, speed = 60),
            ),
        )
        val source = context(initial, listOf(knockout))
        val postKnockout = PublicSingleTurnProjector.project(
            initial,
            knockout,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            source,
        ).single().state

        val replacements = LocalForcedReplacementResolver.resolve(postKnockout, BattleSide.OPPONENT, source).states

        assertEquals(1, replacements.size)
        assertEquals(
            benchId,
            replacements.single().pokemon.single {
                it.side == BattleSide.OPPONENT && it.activeSlot == 0
            }.battlePokemonId,
        )
    }

    @Test
    fun `partial revealed opponent response keeps conditional lookahead active`() {
        val ownMove = move("own", 0, power = 100.0)
        val state = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val original = rank(ownMove)
        val revealedOpponentMove = BattlePublicMoveOptionView(
            "cobblemon:revealed_chip",
            moveDetails(power = 20.0),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
        )
        val partialCatalog = BattlePublicActionCatalogView(
            listOf(BattlePokemonActionCatalogView(OPPONENT_ID, listOf(revealedOpponentMove), moveSetComplete = false)),
        )

        val result = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(original),
            context(state, listOf(ownMove), partialCatalog),
            BattleTrainerProfile.balanced(),
        )

        assertTrue(result.publicResponseIncomplete)
        assertTrue(result.depthCompleted > 0)
        assertTrue(result.ranked.single().lookaheadUtility != 0.0)
    }

    @Test
    fun `deadline truncation keeps only the last fully completed depth`() {
        val ownMove = move("own", 0, power = 20.0)
        val state = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(BattlePublicMoveOptionView("cobblemon:own", moveDetails(power = 20.0), BattlePublicMoveKnowledge.EXACT_OWN)),
                ),
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:opponent",
                            moveDetails(power = 20.0),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        ),
                    ),
                    moveSetComplete = true,
                ),
            ),
        )
        val original = rank(ownMove)

        val result = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(original),
            context(state, listOf(ownMove), catalog),
            BattleTrainerProfile.boss(),
            clockMillis = { Long.MAX_VALUE },
        )

        assertTrue(result.truncated)
        assertEquals(0, result.depthCompleted)
        assertEquals(original, result.ranked.single())
    }

    @Test
    fun `executed lookahead can overturn a static knockout label`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000014")
        val ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.10, speed = 50)
        val bench = pokemon(benchId, BattleSide.ALLY, null, 1.0, speed = 100, types = setOf("ghost"))
        val opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 0.10, speed = 150)
        val state = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(ally, bench, opponent),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val doomedKnockout = move("doomed_knockout", 0, power = 200.0)
        val safeSwitch = BattleActionCandidate(
            actionId = "safe_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    ALLY_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            requireNotNull(doomedKnockout.moveId),
                            requireNotNull(doomedKnockout.moveDetails),
                            BattlePublicMoveKnowledge.EXACT_OWN,
                        ),
                    ),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    benchId,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:bench_hit",
                            moveDetails(power = 80.0, type = "ghost"),
                            BattlePublicMoveKnowledge.EXACT_OWN,
                        ),
                    ),
                    moveSetComplete = true,
                ),
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:opponent_ko",
                            moveDetails(power = 200.0),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        ),
                    ),
                    moveSetComplete = true,
                ),
            ),
        )
        val calculated = PublicBattleTacticalCalculator.calculate(
            context(state, listOf(doomedKnockout, safeSwitch), catalog),
        )
        val base = LocalBattleActionPolicy.rank(calculated, null, BattleTrainerProfile.boss())

        val result = LocalRecursiveLookaheadEvaluator.evaluate(base, calculated, BattleTrainerProfile.boss())

        assertEquals(
            "safe_switch",
            result.ranked.first().outcome.candidate.actionId,
            result.ranked.joinToString { rank ->
                "${rank.outcome.candidate.actionId}:base=${rank.outcome.tacticalUtility}," +
                    "total=${rank.comparisonValue},lookahead=${rank.lookaheadUtility},tier=${rank.decisionTier}"
            },
        )
    }

    @Test
    fun `lookahead records worst known hp retention for a switch`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000019")
        val initial = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, 0, 1.0, speed = 100),
                pokemon(benchId, BattleSide.ALLY, null, 1.0, speed = 100),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val exposedSwitch = BattleActionCandidate(
            actionId = "exposed_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:known_heavy_hit",
                            moveDetails(power = 100.0),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        ),
                    ),
                    moveSetComplete = true,
                ),
            ),
        )
        val calculated = PublicBattleTacticalCalculator.calculate(context(initial, listOf(exposedSwitch), catalog))
        val ranked = LocalBattleActionPolicy.rank(calculated, null, BattleTrainerProfile.boss())

        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, BattleTrainerProfile.boss())

        assertTrue(result.ranked.single().worstResponseHpRetention < 0.25)
    }

    @Test
    fun `lookahead records worst known hp retention for a self setup move`() {
        val initial = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, 0, 0.35, speed = 100),
            opponents = listOf(pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, 1.0, speed = 90)),
        )
        val setup = BattleActionCandidate(
            actionId = "nasty_plot",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:nastyplot",
            moveDetails = BattleMoveCandidateView(
                typeId = "dark",
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELF,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = listOf(
                        BattleMoveEffectView(
                            kind = BattleMoveEffectKind.STAT_STAGE,
                            target = BattleMoveEffectTarget.USER,
                            probability = 1.0,
                            statStages = mapOf("special_attack" to 2),
                        ),
                    ),
                    scriptedBehavior = false,
                ),
            ),
        )
        val catalog = BattlePublicActionCatalogView(
            listOf(
                BattlePokemonActionCatalogView(
                    OPPONENT_ID,
                    listOf(
                        BattlePublicMoveOptionView(
                            "cobblemon:known_finisher",
                            moveDetails(power = 200.0),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        ),
                    ),
                    moveSetComplete = true,
                ),
            ),
        )
        val calculated = PublicBattleTacticalCalculator.calculate(context(initial, listOf(setup), catalog))
        val ranked = LocalBattleActionPolicy.rank(calculated, null, BattleTrainerProfile.boss())

        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, BattleTrainerProfile.boss())

        assertEquals(0.0, result.ranked.single().worstResponseHpRetention, 1e-9)
    }

    private fun rank(candidate: BattleActionCandidate) = LocalBattleActionRank(
        outcome = LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = 0.0,
            expectedDamageFraction = 0.0,
            secureStandardKnockouts = 0,
            executableDamageActions = 1,
            publiclyInert = false,
            entryFaints = false,
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
        ),
        decisionTier = 3,
        comparisonValue = 30_000.0,
    )

    private fun context(
        state: BattleStateView,
        candidates: List<BattleActionCandidate>,
        catalog: BattlePublicActionCatalogView = BattlePublicActionCatalogView.empty(),
    ) = BattleDecisionContext(
        requestId = UUID.fromString("00000000-0000-0000-0000-000000000099"),
        state = state,
        candidates = candidates,
        deadlineEpochMillis = Long.MAX_VALUE,
        publicActionCatalog = catalog,
    )

    private fun state(
        ally: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        opponentRemaining: Int = opponents.size,
        inferences: List<BattleInferenceView> = emptyList(),
    ) = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(ally) + opponents,
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to opponentRemaining),
        observedEvents = emptyList(),
        inferences = inferences,
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        hp: Double,
        speed: Int,
        speedRange: IntRange = speed..speed,
        types: Set<String> = setOf("normal"),
        fainted: Boolean = false,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
        speciesId = "showdown:test",
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = fainted,
        knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(100, 200, 100, 200, 100, speed)
        } else {
            BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(100, 100),
                attack = BattleIntegerRange(200, 200),
                defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(200, 200),
                specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(speedRange.first, speedRange.last),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun move(
        actionId: String,
        priority: Int,
        power: Double,
        type: String = "normal",
        targetSide: BattleSide = BattleSide.OPPONENT,
    ) = BattleActionCandidate(
        actionId = actionId,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$actionId",
        targets = listOf(BattleTargetSlot(targetSide, 0)),
        moveDetails = moveDetails(priority, power, type),
    )

    private fun moveDetails(
        priority: Int = 0,
        power: Double,
        type: String = "normal",
    ) = BattleMoveCandidateView(
        typeId = type,
        damageCategory = BattleMoveDamageCategory.PHYSICAL,
        power = power,
        accuracy = 100.0,
        priority = priority,
        currentPp = 10,
    )

    private fun damageFacts(fraction: Double) = BattleCandidateFactsView(
        baseAccuracyProbability = 1.0,
        typeChartMultiplier = 1.0,
        standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
        standardDamageFractionRange = BattleDamageFractionRange(fraction, fraction),
        standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0),
        standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
        calculationCoverage = BattleCalculationCoverage.PARTIAL,
    )

    private companion object {
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
    }
}
