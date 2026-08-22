package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

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
            move("opponent_ko", 0, power = 200.0),
            context,
        ).single()

        assertEquals(listOf(BattleSide.OPPONENT, BattleSide.ALLY), result.order)
        assertEquals(0.0, result.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction)
        assertEquals(1.0, result.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction)
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
    fun `knockout states keep the replacement and revenge continuation regardless of lost hp`() {
        assertFalse(
            LocalTurnBranchPruner.shouldStopContinuation(
                immediateBoardDelta = -2.8,
                depthRemaining = 3,
                newlyLostAllyHpBefore = 1.0,
            ),
            "even a full-HP sacrifice can be the correct route to a safe revenge attacker",
        )
        assertFalse(
            LocalTurnBranchPruner.shouldStopContinuation(
                immediateBoardDelta = -2.8,
                depthRemaining = 1,
                newlyLostAllyHpBefore = 1.0,
            ),
            "the final simulated turn already has no deeper continuation to prune",
        )
        assertFalse(
            LocalTurnBranchPruner.shouldStopContinuation(
                immediateBoardDelta = -2.8,
                depthRemaining = 3,
                newlyLostAllyHpBefore = 0.2,
            ),
            "a low-HP intentional sacrifice must also keep its revenge-kill continuation",
        )
        assertFalse(
            LocalTurnBranchPruner.shouldStopContinuation(
                immediateBoardDelta = -0.6,
                depthRemaining = 3,
                newlyLostAllyHpBefore = null,
            ),
            "ordinary bad turns and failed reads are not catastrophic branches",
        )
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
            move("current-hit", 0, power = 20.0),
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
    ) = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(ally) + opponents,
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to opponentRemaining),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        hp: Double,
        speed: Int,
        types: Set<String> = setOf("normal"),
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
        fainted = false,
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
                speed = BattleIntegerRange(speed, speed),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun move(
        actionId: String,
        priority: Int,
        power: Double,
        type: String = "normal",
    ) = BattleActionCandidate(
        actionId = actionId,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$actionId",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
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

    private companion object {
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
    }
}
