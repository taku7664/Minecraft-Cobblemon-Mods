package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeProductForcedSwitchContinuationTest {
    @Test
    fun `faint replacement request and replacement choice reconcile as two native decisions`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = forcedSwitchBattle()
            val opening = engine.createBattle(definition)
            val allyMove = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening)
                .single { it.moveId == "splash" && it.mechanic == null }
            val opponentMove = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening)
                .single { it.moveId == "tackle" && it.mechanic == null }
            val forced = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyMove, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(opponentMove, BattleSide.OPPONENT, opening),
            )
            val forcedSwitch = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced).single()
            val opponentWait = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, forced).single()
            val afterReplacement = engine.branch(
                forced.snapshotJson,
                NativeShowdownChoiceEncoder.encode(forcedSwitch, BattleSide.ALLY, forced),
                NativeShowdownChoiceEncoder.encode(opponentWait, BattleSide.OPPONENT, forced),
            )

            assertEquals("switch", forced.requestState)
            assertEquals("move", afterReplacement.requestState)
            assertTrue(afterReplacement.p1Active.single().uuid == ALLY_RESERVE.toString())

            val moveEvent = BattleObservedEventView(
                sequence = 1,
                turn = opening.turn,
                kind = BattleObservedEventKind.MOVE_USED,
                actorPokemonId = OPPONENT,
                publicValueId = "tackle",
                actorSlot = 0,
            )
            val switchEvent = BattleObservedEventView(
                sequence = 2,
                turn = forced.turn,
                kind = BattleObservedEventKind.SWITCHED,
                actorPokemonId = ALLY_RESERVE,
                actorSlot = 0,
            )
            val openingContext = context(opening, publicTemplate(definition),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening))
            val forcedContext = context(forced, publicTemplate(definition, listOf(moveEvent)),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced))
            val replacementContext = context(afterReplacement,
                publicTemplate(definition, listOf(moveEvent, switchEvent)),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterReplacement))
            val initial = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("forced-switch-world", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, opening),
                    publicContext = openingContext,
                )),
                publicTurn = opening.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = allyMove,
            )
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val skippedOwnDecision = reconciler.reconcile(initial, replacementContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.NO_CONSISTENT_WORLD, skippedOwnDecision.status,
                "The native replay must not invent the ally's forced replacement")

            val forcedResult = reconciler.reconcile(initial, forcedContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, forcedResult.status,
                forcedResult.rootIssues.toString())
            assertEquals(forced.snapshotJson,
                forcedResult.sessionState?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)

            val replacementResult = reconciler.reconcile(
                requireNotNull(forcedResult.sessionState).withPendingOwnAction(forcedSwitch),
                replacementContext,
                Long.MAX_VALUE,
            )

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, replacementResult.status,
                replacementResult.rootIssues.toString())
            assertEquals(afterReplacement.snapshotJson,
                replacementResult.sessionState?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)
            assertEquals(afterReplacement.turn, replacementResult.sessionState?.publicTurn)
        }
    }

    @Test
    fun `pivot replacement reconciles the opponent move revealed after its wait request`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = pivotBattle()
            val opening = engine.createBattle(definition)
            val pivotMove = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening)
                .single { it.moveId == "uturn" && it.mechanic == null }
            val opponentMove = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening)
                .single { it.moveId == "splash" && it.mechanic == null }
            val pivotRequest = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(pivotMove, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(opponentMove, BattleSide.OPPONENT, opening),
            )
            val replacement = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, pivotRequest).single()
            val wait = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, pivotRequest).single()
            val afterReplacement = engine.branch(
                pivotRequest.snapshotJson,
                NativeShowdownChoiceEncoder.encode(replacement, BattleSide.ALLY, pivotRequest),
                NativeShowdownChoiceEncoder.encode(wait, BattleSide.OPPONENT, pivotRequest),
            )
            assertEquals("switch", pivotRequest.requestState)
            assertEquals("move", afterReplacement.requestState)

            val pivotEvent = BattleObservedEventView(
                sequence = 1,
                turn = opening.turn,
                kind = BattleObservedEventKind.MOVE_USED,
                actorPokemonId = ALLY_LEAD,
                publicValueId = "uturn",
                actorSlot = 0,
            )
            val switchEvent = BattleObservedEventView(
                sequence = 2,
                turn = pivotRequest.turn,
                kind = BattleObservedEventKind.SWITCHED,
                actorPokemonId = ALLY_RESERVE,
                actorSlot = 0,
            )
            val delayedOpponentMove = BattleObservedEventView(
                sequence = 3,
                turn = pivotRequest.turn,
                kind = BattleObservedEventKind.MOVE_USED,
                actorPokemonId = OPPONENT,
                publicValueId = "splash",
                actorSlot = 0,
            )
            val openingContext = context(opening, publicTemplate(definition),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening))
            val pivotContext = context(pivotRequest, publicTemplate(definition, listOf(pivotEvent)),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, pivotRequest))
            val replacementContext = context(
                afterReplacement,
                publicTemplate(definition, listOf(pivotEvent, switchEvent, delayedOpponentMove)),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterReplacement),
            )
            val initial = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("pivot-world", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, opening),
                    publicContext = openingContext,
                )),
                publicTurn = opening.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = pivotMove,
            )
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val pivotResult = reconciler.reconcile(initial, pivotContext, Long.MAX_VALUE)
            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, pivotResult.status,
                pivotResult.rootIssues.toString())

            val replacementResult = reconciler.reconcile(
                requireNotNull(pivotResult.sessionState).withPendingOwnAction(replacement),
                replacementContext,
                Long.MAX_VALUE,
            )

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, replacementResult.status,
                replacementResult.observedActionIssues.toString())
            assertEquals(listOf(afterReplacement.snapshotJson),
                replacementResult.sessionState?.worlds?.map { it.rootSnapshot.frame.snapshotJson })
        }
    }

    @Test
    fun `opponent pivot replacement is replayed without an intermediate ally decision`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = opponentPivotBattle()
            val opening = engine.createBattle(definition)
            val allyMove = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening)
                .single { it.moveId == "splash" && it.mechanic == null }
            val pivotMove = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening)
                .single { it.moveId == "uturn" && it.mechanic == null }
            val pivotRequest = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyMove, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(pivotMove, BattleSide.OPPONENT, opening),
            )
            val allyWait = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, pivotRequest).single()
            val opponentReplacement = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, pivotRequest)
                .single()
            val afterReplacement = engine.branch(
                pivotRequest.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyWait, BattleSide.ALLY, pivotRequest),
                NativeShowdownChoiceEncoder.encode(opponentReplacement, BattleSide.OPPONENT, pivotRequest),
            )

            assertEquals("switch", pivotRequest.requestState)
            assertEquals("move", afterReplacement.requestState)
            assertEquals(OPPONENT_RESERVE.toString(), afterReplacement.p2Active.single().uuid)

            val events = listOf(
                BattleObservedEventView(
                    sequence = 1,
                    turn = opening.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = OPPONENT,
                    publicValueId = "uturn",
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 2,
                    turn = pivotRequest.turn,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = OPPONENT_RESERVE,
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 3,
                    turn = pivotRequest.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = ALLY_LEAD,
                    publicValueId = "splash",
                    actorSlot = 0,
                ),
            )
            val openingContext = context(
                opening,
                publicTemplate(definition),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening),
            )
            val finalContext = context(
                afterReplacement,
                publicTemplate(definition, events),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterReplacement),
            )
            val initial = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("opponent-pivot-world", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, opening),
                    publicContext = openingContext,
                )),
                publicTurn = opening.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = allyMove,
            )
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val result = reconciler.reconcile(initial, finalContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}")
            assertEquals(afterReplacement.snapshotJson,
                result.sessionState?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)
        }
    }

    @Test
    fun `double partial forced replacement preserves the live partner slot`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = partialDoubleReplacementBattle()
            val opening = engine.createBattle(definition)
            val allyMoves = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening)
                .single { action ->
                    action.moveIdsBySlot() == listOf("splash", "splash") &&
                        action.componentActions.none { it.mechanic != null }
                }
            val opponentMoves = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening)
                .single { action ->
                    action.moveIdsBySlot() == listOf("rockslide", "splash") &&
                        action.componentActions.none { it.mechanic != null }
                }
            val forced = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyMoves, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(opponentMoves, BattleSide.OPPONENT, opening),
            )
            val replacement = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced)
                .single { action -> action.componentActions.any { it.switchPokemonId == ALLY_RESERVE } }
            val opponentWait = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, forced).single()
            val afterReplacement = engine.branch(
                forced.snapshotJson,
                NativeShowdownChoiceEncoder.encode(replacement, BattleSide.ALLY, forced),
                NativeShowdownChoiceEncoder.encode(opponentWait, BattleSide.OPPONENT, forced),
            )

            assertEquals("switch", forced.requestState)
            assertEquals("switch 3, pass",
                NativeShowdownChoiceEncoder.encode(replacement, BattleSide.ALLY, forced))
            assertEquals(listOf(ALLY_RESERVE.toString(), ALLY_SECOND.toString()),
                afterReplacement.p1Active.sortedBy { it.activeSlot }.map { it.uuid })

            val firstTurnEvents = listOf(
                moveEvent(1, OPPONENT, 0, "rockslide", opening.turn),
                moveEvent(2, ALLY_SECOND, 1, "splash", opening.turn),
                moveEvent(3, OPPONENT_SECOND, 1, "splash", opening.turn),
            )
            val replacementEvents = firstTurnEvents + BattleObservedEventView(
                sequence = 4,
                turn = forced.turn,
                kind = BattleObservedEventKind.SWITCHED,
                actorPokemonId = ALLY_RESERVE,
                actorSlot = 0,
            )
            val openingContext = context(
                opening,
                publicTemplate(definition),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening),
            )
            val forcedContext = context(
                forced,
                publicTemplate(definition, firstTurnEvents),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced),
            )
            val replacementContext = context(
                afterReplacement,
                publicTemplate(definition, replacementEvents),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterReplacement),
            )
            val initial = initialSession(engine.rulesFingerprint, definition, opening, openingContext, allyMoves)
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }
            val observedOpponent = NativeObservedTurnActionMatcher.match(
                BattleFormat.DOUBLE,
                BattleSide.OPPONENT,
                opening,
                NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening),
                firstTurnEvents,
            )
            assertEquals(listOf(opponentMoves.actionId), observedOpponent.actions.map { it.actionId },
                observedOpponent.issues.toString())

            val forcedResult = reconciler.reconcile(initial, forcedContext, Long.MAX_VALUE)
            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, forcedResult.status,
                "root=${forcedResult.rootIssues}, observed=${forcedResult.observedActionIssues}")
            assertEquals(1, forcedResult.sessionState?.worlds?.size,
                forcedResult.sessionState?.worlds?.joinToString { it.key.lineage })

            val replacementResult = reconciler.reconcile(
                requireNotNull(forcedResult.sessionState).withPendingOwnAction(replacement),
                replacementContext,
                Long.MAX_VALUE,
            )

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, replacementResult.status,
                "root=${replacementResult.rootIssues}, observed=${replacementResult.observedActionIssues}")
            assertEquals(1, replacementResult.sessionState?.worlds?.size,
                replacementResult.sessionState?.worlds?.joinToString { it.key.lineage })
            assertEquals(afterReplacement.snapshotJson,
                replacementResult.sessionState?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)
        }
    }

    @Test
    fun `double simultaneous partial replacements preserve both live partner slots`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = simultaneousDoubleReplacementBattle()
            val opening = engine.createBattle(definition)
            val allyMoves = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening)
                .single { action ->
                    NativeShowdownChoiceEncoder.encode(action, BattleSide.ALLY, opening) == "move 1 2, move 1"
                }
            val opponentMoves = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening)
                .single { action ->
                    NativeShowdownChoiceEncoder.encode(action, BattleSide.OPPONENT, opening) == "move 1 2, move 1"
                }
            val forced = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyMoves, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(opponentMoves, BattleSide.OPPONENT, opening),
            )
            val allyReplacement = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced)
                .single { action -> action.componentActions.any { it.switchPokemonId == ALLY_RESERVE } }
            val opponentReplacement = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, forced)
                .single { action -> action.componentActions.any { it.switchPokemonId == OPPONENT_RESERVE } }
            val afterReplacement = engine.branch(
                forced.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyReplacement, BattleSide.ALLY, forced),
                NativeShowdownChoiceEncoder.encode(opponentReplacement, BattleSide.OPPONENT, forced),
            )

            assertEquals("switch", forced.requestState)
            assertEquals("pass, switch 3",
                NativeShowdownChoiceEncoder.encode(allyReplacement, BattleSide.ALLY, forced))
            assertEquals("pass, switch 3",
                NativeShowdownChoiceEncoder.encode(opponentReplacement, BattleSide.OPPONENT, forced))
            assertEquals(listOf(ALLY_LEAD.toString(), ALLY_RESERVE.toString()),
                afterReplacement.p1Active.sortedBy { it.activeSlot }.map { it.uuid })
            assertEquals(listOf(OPPONENT.toString(), OPPONENT_RESERVE.toString()),
                afterReplacement.p2Active.sortedBy { it.activeSlot }.map { it.uuid })

            val firstTurnEvents = listOf(
                moveEvent(1, ALLY_LEAD, 0, "tackle", opening.turn, listOf(OPPONENT_SECOND)),
                moveEvent(2, OPPONENT, 0, "tackle", opening.turn, listOf(ALLY_SECOND)),
            )
            val replacementEvents = firstTurnEvents + listOf(
                BattleObservedEventView(
                    sequence = 3,
                    turn = forced.turn,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = ALLY_RESERVE,
                    actorSlot = 1,
                ),
                BattleObservedEventView(
                    sequence = 4,
                    turn = forced.turn,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = OPPONENT_RESERVE,
                    actorSlot = 1,
                ),
            )
            val openingContext = context(
                opening,
                publicTemplate(definition),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening),
            )
            val forcedContext = context(
                forced,
                publicTemplate(definition, firstTurnEvents),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced),
            )
            val replacementContext = context(
                afterReplacement,
                publicTemplate(definition, replacementEvents),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterReplacement),
            )
            val initial = initialSession(engine.rulesFingerprint, definition, opening, openingContext, allyMoves)
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val observedOpponent = NativeObservedTurnActionMatcher.match(
                BattleFormat.DOUBLE,
                BattleSide.OPPONENT,
                opening,
                NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening),
                firstTurnEvents,
            )
            assertTrue(observedOpponent.issues.isEmpty(), observedOpponent.issues.toString())
            assertTrue(observedOpponent.actions.any { it.actionId == opponentMoves.actionId })
            assertTrue(observedOpponent.actions.all { action ->
                action.componentActions.single { it.actorSlot == 0 }.targets.single().slot == 1
            }, "Every retained opponent command must target the publicly observed right ally slot")

            val forcedResult = reconciler.reconcile(initial, forcedContext, Long.MAX_VALUE)
            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, forcedResult.status,
                "root=${forcedResult.rootIssues}, observed=${forcedResult.observedActionIssues}")
            assertEquals(1, forcedResult.sessionState?.worlds?.size,
                "Absent Tera evidence and the public forced board must remove every alternate command")

            val replacementResult = reconciler.reconcile(
                requireNotNull(forcedResult.sessionState).withPendingOwnAction(allyReplacement),
                replacementContext,
                Long.MAX_VALUE,
            )

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, replacementResult.status,
                "root=${replacementResult.rootIssues}, observed=${replacementResult.observedActionIssues}")
            val finalWorlds = requireNotNull(replacementResult.sessionState).worlds
            assertEquals(1, finalWorlds.size)
            assertEquals(1.0, finalWorlds.sumOf { it.probability }, 1e-12)
            assertEquals(afterReplacement.snapshotJson, finalWorlds.single().rootSnapshot.frame.snapshotJson)
        }
    }

    @Test
    fun `double two-slot forced replacement submits two distinct reserves atomically`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = fullDoubleReplacementBattle()
            val opening = engine.createBattle(definition)
            val allyMoves = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening)
                .single { action ->
                    action.moveIdsBySlot() == listOf("splash", "splash") &&
                        action.componentActions.none { it.mechanic != null }
                }
            val opponentMoves = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening)
                .single { action ->
                    action.moveIdsBySlot() == listOf("rockslide", "splash") &&
                        action.componentActions.none { it.mechanic != null }
                }
            val forced = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyMoves, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(opponentMoves, BattleSide.OPPONENT, opening),
            )
            val replacement = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced)
                .single { action ->
                    NativeShowdownChoiceEncoder.encode(action, BattleSide.ALLY, forced) == "switch 3, switch 4"
                }
            val opponentWait = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, forced).single()
            val afterReplacement = engine.branch(
                forced.snapshotJson,
                NativeShowdownChoiceEncoder.encode(replacement, BattleSide.ALLY, forced),
                NativeShowdownChoiceEncoder.encode(opponentWait, BattleSide.OPPONENT, forced),
            )

            assertEquals("switch", forced.requestState)
            assertEquals(listOf(ALLY_RESERVE.toString(), ALLY_FOURTH.toString()),
                afterReplacement.p1Active.sortedBy { it.activeSlot }.map { it.uuid })

            val firstTurnEvents = listOf(
                moveEvent(1, OPPONENT, 0, "rockslide", opening.turn),
                moveEvent(2, OPPONENT_SECOND, 1, "splash", opening.turn),
            )
            val replacementEvents = firstTurnEvents + listOf(
                BattleObservedEventView(
                    sequence = 3,
                    turn = forced.turn,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = ALLY_RESERVE,
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 4,
                    turn = forced.turn,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = ALLY_FOURTH,
                    actorSlot = 1,
                ),
            )
            val openingContext = context(
                opening,
                publicTemplate(definition),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening),
            )
            val forcedContext = context(
                forced,
                publicTemplate(definition, firstTurnEvents),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, forced),
            )
            val replacementContext = context(
                afterReplacement,
                publicTemplate(definition, replacementEvents),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterReplacement),
            )
            val initial = initialSession(engine.rulesFingerprint, definition, opening, openingContext, allyMoves)
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val forcedResult = reconciler.reconcile(initial, forcedContext, Long.MAX_VALUE)
            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, forcedResult.status,
                "root=${forcedResult.rootIssues}, observed=${forcedResult.observedActionIssues}")

            val replacementResult = reconciler.reconcile(
                requireNotNull(forcedResult.sessionState).withPendingOwnAction(replacement),
                replacementContext,
                Long.MAX_VALUE,
            )

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, replacementResult.status,
                "root=${replacementResult.rootIssues}, observed=${replacementResult.observedActionIssues}")
            assertEquals(afterReplacement.snapshotJson,
                replacementResult.sessionState?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)
        }
    }

    private fun context(
        frame: jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame,
        template: BattleStateView,
        candidates: List<jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate>,
    ) = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("request:${frame.snapshotJson}".toByteArray()),
        state = NativeBattleStateAdapter.adapt(frame, template),
        candidates = candidates,
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun publicTemplate(
        definition: NativeBattleDefinition,
        events: List<BattleObservedEventView> = emptyList(),
    ): BattleStateView {
        val format = if (definition.formatId == "cobblemondoubles") BattleFormat.DOUBLE else BattleFormat.SINGLE
        val activeSlots = if (format == BattleFormat.DOUBLE) 2 else 1
        return BattleStateView(
        battleId = BATTLE,
        format = format,
        turn = 1,
        pokemon = definition.p1Team.mapIndexed { index, set ->
            publicPokemon(UUID.fromString(set.uuid), BattleSide.ALLY, index.takeIf { index < activeSlots },
                set.species, set.level)
        } + definition.p2Team.mapIndexed { index, set ->
            publicPokemon(UUID.fromString(set.uuid), BattleSide.OPPONENT, index.takeIf { index < activeSlots },
                set.species, set.level)
        },
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(
            BattleSide.ALLY to definition.p1Team.size,
            BattleSide.OPPONENT to definition.p2Team.size,
        ),
        observedEvents = events,
        inferences = emptyList(),
    )
    }

    private fun publicPokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        species: String,
        level: Int,
    ) = BattlePokemonStateView(
        id, side, activeSlot, "cobblemon:$species", null, level, 1.0, null,
        emptyMap(), emptySet(), null, null, false,
    )

    private fun forcedSwitchBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(3, 5, 7, 11),
        p1Team = listOf(
            NativePokemonSet("Lead", "Magikarp", listOf("splash"), "swiftswim",
                uuid = ALLY_LEAD.toString(), level = 1),
            NativePokemonSet("Reserve", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_RESERVE.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Attacker", "Mew", listOf("tackle"), "synchronize",
                uuid = OPPONENT.toString(), level = 100),
        ),
    )

    private fun pivotBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(13, 17, 19, 23),
        p1Team = listOf(
            NativePokemonSet("Pivot", "Ninjask", listOf("uturn"), "speedboost",
                uuid = ALLY_LEAD.toString(), level = 50),
            NativePokemonSet("Reserve", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_RESERVE.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Waiting", "Slowpoke", listOf("splash"), "oblivious",
                uuid = OPPONENT.toString(), teraType = "Fire", level = 50),
        ),
    )

    private fun opponentPivotBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(29, 31, 37, 41),
        p1Team = listOf(
            NativePokemonSet("Waiting", "Slowpoke", listOf("splash"), "oblivious",
                uuid = ALLY_LEAD.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Pivot", "Ninjask", listOf("uturn"), "speedboost",
                uuid = OPPONENT.toString(), level = 50),
            NativePokemonSet("Reserve", "Mew", listOf("splash"), "synchronize",
                uuid = OPPONENT_RESERVE.toString(), level = 50),
        ),
    )

    private fun partialDoubleReplacementBattle() = NativeBattleDefinition(
        formatId = "cobblemondoubles",
        seed = listOf(43, 47, 53, 59),
        p1Team = listOf(
            NativePokemonSet("Fragile", "Magikarp", listOf("splash"), "swiftswim",
                uuid = ALLY_LEAD.toString(), level = 1),
            NativePokemonSet("Partner", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_SECOND.toString(), level = 50),
            NativePokemonSet("Reserve", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_RESERVE.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Attacker", "Mew", listOf("rockslide"), "synchronize",
                uuid = OPPONENT.toString(), level = 100),
            NativePokemonSet("Partner", "Shuckle", listOf("splash"), "sturdy",
                uuid = OPPONENT_SECOND.toString(), level = 50),
        ),
    )

    private fun simultaneousDoubleReplacementBattle() = NativeBattleDefinition(
        formatId = "cobblemondoubles",
        seed = listOf(61, 67, 71, 73),
        p1Team = listOf(
            NativePokemonSet("Attacker", "Mew", listOf("tackle"), "synchronize",
                uuid = ALLY_LEAD.toString(), level = 100),
            NativePokemonSet("Fragile", "Magikarp", listOf("splash"), "swiftswim",
                uuid = ALLY_SECOND.toString(), level = 1),
            NativePokemonSet("Reserve", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_RESERVE.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Attacker", "Mew", listOf("tackle"), "synchronize",
                uuid = OPPONENT.toString(), level = 100),
            NativePokemonSet("Fragile", "Magikarp", listOf("splash"), "swiftswim",
                uuid = OPPONENT_SECOND.toString(), level = 1),
            NativePokemonSet("Reserve", "Mew", listOf("splash"), "synchronize",
                uuid = OPPONENT_RESERVE.toString(), level = 50),
        ),
    )

    private fun fullDoubleReplacementBattle() = NativeBattleDefinition(
        formatId = "cobblemondoubles",
        seed = listOf(79, 83, 89, 97),
        p1Team = listOf(
            NativePokemonSet("Fragile Left", "Magikarp", listOf("splash"), "swiftswim",
                uuid = ALLY_LEAD.toString(), level = 1),
            NativePokemonSet("Fragile Right", "Magikarp", listOf("splash"), "swiftswim",
                uuid = ALLY_SECOND.toString(), level = 1),
            NativePokemonSet("Reserve Left", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_RESERVE.toString(), level = 50),
            NativePokemonSet("Reserve Right", "Mew", listOf("splash"), "synchronize",
                uuid = ALLY_FOURTH.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Attacker", "Mew", listOf("rockslide"), "synchronize",
                uuid = OPPONENT.toString(), level = 100),
            NativePokemonSet("Partner", "Shuckle", listOf("splash"), "sturdy",
                uuid = OPPONENT_SECOND.toString(), level = 50),
        ),
    )

    private fun jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate.moveIdsBySlot(): List<String?> =
        if (kind == BattleActionKind.COMPOSITE) {
            componentActions.sortedBy { it.actorSlot }.map { it.moveId }
        } else {
            listOf(moveId)
        }

    private fun moveEvent(
        sequence: Long,
        actor: UUID,
        actorSlot: Int,
        moveId: String,
        turn: Int,
        targets: List<UUID> = emptyList(),
    ) = BattleObservedEventView(
        sequence = sequence,
        turn = turn,
        kind = BattleObservedEventKind.MOVE_USED,
        actorPokemonId = actor,
        targetPokemonIds = targets,
        publicValueId = moveId,
        actorSlot = actorSlot,
    )

    private fun initialSession(
        rulesFingerprint: String,
        definition: NativeBattleDefinition,
        opening: jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame,
        openingContext: BattleDecisionContext,
        pendingOwnAction: jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate,
    ) = NativeProductSessionState(
        battleId = BATTLE,
        format = openingContext.state.format,
        rulesFingerprint = rulesFingerprint,
        worlds = listOf(NativeProductSessionWorld(
            key = NativeSearchWorldKey("replacement-world", 0),
            probability = 1.0,
            definition = definition,
            rootSnapshot = NativeProductRootSnapshot(rulesFingerprint, opening),
            publicContext = openingContext,
        )),
        publicTurn = opening.turn,
        lastObservedEventSequence = null,
        pendingOwnAction = pendingOwnAction,
    )

    private fun extractBundledShowdown(targetRoot: Path): Path {
        Files.createDirectories(targetRoot)
        val resource = requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip")) {
            "Cobblemon embedded Showdown is missing from the test runtime"
        }
        var extracted = 0L
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = EmbeddedShowdownOracle.entryPath(targetRoot, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024) {
                                "Embedded archive exceeds extraction limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return targetRoot.toAbsolutePath().normalize()
    }

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY_LEAD: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val ALLY_RESERVE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val ALLY_SECOND: UUID = UUID.fromString("00000000-0000-0000-0000-000000000103")
        val ALLY_FOURTH: UUID = UUID.fromString("00000000-0000-0000-0000-000000000104")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val OPPONENT_RESERVE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val OPPONENT_SECOND: UUID = UUID.fromString("00000000-0000-0000-0000-000000000203")
    }
}
