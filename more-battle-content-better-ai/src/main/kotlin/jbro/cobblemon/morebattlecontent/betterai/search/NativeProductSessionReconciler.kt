package jbro.cobblemon.morebattlecontent.betterai.search

import java.security.MessageDigest
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRootActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRuntimeService

internal enum class NativeProductSessionReconcileStatus {
    AVAILABLE,
    BATTLE_CONTEXT_MISMATCH,
    PUBLIC_TURN_REGRESSION,
    PUBLIC_EVENT_HISTORY_GAP,
    PENDING_OWN_ACTION_MISSING,
    DEADLINE_EXHAUSTED,
    RUNTIME_UNAVAILABLE,
    RULES_GENERATION_MISMATCH,
    ROOT_ACTION_MISMATCH,
    OBSERVED_ACTION_MISMATCH,
    ROOT_STATE_INCONSISTENT,
    NATIVE_EXECUTION_FAILURE,
    NO_CONSISTENT_WORLD,
}

internal data class NativeProductSessionReconciliation(
    val status: NativeProductSessionReconcileStatus,
    val sessionState: NativeProductSessionState? = null,
    val failedWorldId: String? = null,
    val rootIssues: List<NativeBattleRootIssue> = emptyList(),
    val observedActionIssues: List<NativeObservedTurnActionIssue> = emptyList(),
    val failure: Throwable? = null,
) {
    init {
        require((status == NativeProductSessionReconcileStatus.AVAILABLE) == (sessionState != null))
    }
}

private typealias NativeProductSessionLease = (
    deadlineNanos: Long,
    action: (NativeBranchWorker) -> NativeProductSessionReconciliation,
) -> NativeProductSessionReconciliation?

/**
 * Advances retained native roots with the command selected by this Brain and public opponent
 * command evidence, then conditions every descendant on the new public board.
 *
 * Public mismatches eliminate posterior worlds. Structural frame failures invalidate the native
 * operation instead of being mistaken for ordinary evidence against a hypothesis.
 */
internal class NativeProductSessionReconciler(
    private val nanoTime: () -> Long = System::nanoTime,
    private val lease: NativeProductSessionLease = { deadlineNanos, action ->
        NativeShowdownRuntimeService.withWorker(deadlineNanos, action)
    },
) {
    fun reconcile(
        session: NativeProductSessionState,
        currentContext: BattleDecisionContext,
        deadlineNanos: Long,
    ): NativeProductSessionReconciliation {
        if (currentContext.state.battleId != session.battleId ||
            currentContext.state.format != session.format
        ) {
            return failure(NativeProductSessionReconcileStatus.BATTLE_CONTEXT_MISMATCH)
        }
        if (currentContext.state.turn < session.publicTurn) {
            return failure(NativeProductSessionReconcileStatus.PUBLIC_TURN_REGRESSION)
        }
        val pendingOwnAction = session.pendingOwnAction
            ?: return failure(NativeProductSessionReconcileStatus.PENDING_OWN_ACTION_MISSING)
        val eventWindow = eventsAfter(session.lastObservedEventSequence, currentContext.state.observedEvents)
            ?: return failure(NativeProductSessionReconcileStatus.PUBLIC_EVENT_HISTORY_GAP)
        if (deadlineReached(deadlineNanos)) {
            return failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
        }

        val leased = try {
            lease(deadlineNanos) { worker ->
                if (worker.rulesFingerprint != session.rulesFingerprint) {
                    return@lease failure(NativeProductSessionReconcileStatus.RULES_GENERATION_MISMATCH)
                }
                val descendants = mutableListOf<Descendant>()
                var firstObservedMismatch: Pair<String, List<NativeObservedTurnActionIssue>>? = null
                for (world in session.worlds.sortedWith(WORLD_ORDER)) {
                    if (deadlineReached(deadlineNanos)) {
                        return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                    }
                    val root = world.rootSnapshot.frame
                    val deferred = validateDeferredActions(world, root, eventWindow)
                    if (deferred.issues.isNotEmpty()) {
                        if (firstObservedMismatch == null) {
                            firstObservedMismatch = world.key.hypothesisId to deferred.issues
                        }
                        continue
                    }
                    val currentEvents = eventWindow.filterNot { it.sequence in deferred.consumedSequences }
                    val ownNativeActions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root)
                    val ownMapping = NativeRootActionMatcher.match(
                        session.format,
                        listOf(pendingOwnAction),
                        ownNativeActions,
                    )
                    val ownNativeAction = ownMapping.productToNative[pendingOwnAction.actionId]
                    if (ownNativeAction == null ||
                        pendingOwnAction.actionId in ownMapping.ambiguousProductActionIds
                    ) {
                        return@lease failure(
                            NativeProductSessionReconcileStatus.ROOT_ACTION_MISMATCH,
                            failedWorldId = world.key.hypothesisId,
                        )
                    }

                    val opponentNativeActions = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, root)
                    val observed = NativeObservedTurnActionMatcher.match(
                        session.format,
                        BattleSide.OPPONENT,
                        root,
                        opponentNativeActions,
                        currentEvents,
                    )
                    if (observed.issues.isNotEmpty()) {
                        if (firstObservedMismatch == null) {
                            firstObservedMismatch = world.key.hypothesisId to observed.issues
                        }
                        continue
                    }

                    val ownChoice = NativeShowdownChoiceEncoder.encode(ownNativeAction, BattleSide.ALLY, root)
                    val compatibleFrames = linkedMapOf<DescendantIdentity, CompatibleFrame>()
                    for (opponentAction in observed.actions) {
                        if (deadlineReached(deadlineNanos)) {
                            return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                        }
                        val opponentChoice = NativeShowdownChoiceEncoder.encode(
                            opponentAction,
                            BattleSide.OPPONENT,
                            root,
                        )
                        val next = worker.branch(root.snapshotJson, ownChoice, opponentChoice)
                        val issues = NativeBattleRootValidator.validate(
                            world.definition,
                            next,
                            currentContext.state,
                        )
                        val structural = issues.filter { it.code in STRUCTURAL_ROOT_ISSUES }
                        if (structural.isNotEmpty()) {
                            return@lease failure(
                                NativeProductSessionReconcileStatus.ROOT_STATE_INCONSISTENT,
                                failedWorldId = world.key.hypothesisId,
                                rootIssues = structural,
                            )
                        }
                        if (issues.isEmpty()) {
                            val compatible = CompatibleFrame(
                                frame = next,
                                deferredAllyAction = deferredForNextRequest(
                                    BattleSide.ALLY,
                                    root,
                                    next,
                                    world.deferredAllyAction.takeUnless {
                                        BattleSide.ALLY in deferred.consumedSides
                                    },
                                    ownNativeAction,
                                    currentEvents,
                                ),
                                deferredOpponentAction = deferredForNextRequest(
                                    BattleSide.OPPONENT,
                                    root,
                                    next,
                                    world.deferredOpponentAction.takeUnless {
                                        BattleSide.OPPONENT in deferred.consumedSides
                                    },
                                    opponentAction,
                                    currentEvents,
                                ),
                            )
                            compatibleFrames.putIfAbsent(compatible.identity, compatible)
                        }
                    }
                    if (compatibleFrames.isEmpty()) continue

                    // No public action evidence distinguishes these descendants. Until a versioned
                    // opponent-policy likelihood exists, preserve them with the uninformative prior
                    // instead of inventing one command or multiplying the parent probability mass.
                    val probability = world.probability / compatibleFrames.size.toDouble()
                    compatibleFrames.values.forEach { compatible ->
                        descendants += Descendant(
                            world = world,
                            frame = compatible.frame,
                            probability = probability,
                            split = compatibleFrames.size > 1,
                            deferredAllyAction = compatible.deferredAllyAction,
                            deferredOpponentAction = compatible.deferredOpponentAction,
                        )
                    }
                }
                if (descendants.isEmpty()) {
                    val observedMismatch = firstObservedMismatch
                    return@lease if (observedMismatch != null) {
                        failure(
                            NativeProductSessionReconcileStatus.OBSERVED_ACTION_MISMATCH,
                            failedWorldId = observedMismatch.first,
                            observedActionIssues = observedMismatch.second,
                        )
                    } else {
                        failure(NativeProductSessionReconcileStatus.NO_CONSISTENT_WORLD)
                    }
                }

                val retainedMass = descendants.sumOf(Descendant::probability)
                if (!retainedMass.isFinite() || retainedMass <= 0.0) {
                    return@lease failure(NativeProductSessionReconcileStatus.NO_CONSISTENT_WORLD)
                }
                val updatedWorlds = descendants.map { descendant ->
                    val previous = descendant.world
                    val key = if (descendant.split) {
                        previous.key.copy(lineage = extendLineage(previous.key.lineage, descendant.identity))
                    } else {
                        previous.key
                    }
                    NativeProductSessionWorld(
                        key = key,
                        probability = descendant.probability / retainedMass,
                        definition = previous.definition,
                        rootSnapshot = NativeProductRootSnapshot(session.rulesFingerprint, descendant.frame),
                        publicContext = currentContext.copy(
                            publicActionCatalog = previous.publicContext.publicActionCatalog,
                            opponentTeamPreview = currentContext.opponentTeamPreview
                                ?: previous.publicContext.opponentTeamPreview,
                            exactOwnTeam = currentContext.exactOwnTeam ?: previous.publicContext.exactOwnTeam,
                        ),
                        deferredAllyAction = descendant.deferredAllyAction,
                        deferredOpponentAction = descendant.deferredOpponentAction,
                    )
                }
                NativeProductSessionReconciliation(
                    status = NativeProductSessionReconcileStatus.AVAILABLE,
                    sessionState = NativeProductSessionState(
                        battleId = session.battleId,
                        format = session.format,
                        rulesFingerprint = session.rulesFingerprint,
                        worlds = updatedWorlds,
                        publicTurn = currentContext.state.turn,
                        lastObservedEventSequence = currentContext.state.observedEvents.lastOrNull()?.sequence
                            ?: session.lastObservedEventSequence,
                        pendingOwnAction = null,
                    ),
                )
            }
        } catch (failure: Exception) {
            return failure(NativeProductSessionReconcileStatus.NATIVE_EXECUTION_FAILURE, failure = failure)
        } catch (failure: LinkageError) {
            return failure(NativeProductSessionReconcileStatus.NATIVE_EXECUTION_FAILURE, failure = failure)
        }

        return leased ?: failure(
            if (deadlineReached(deadlineNanos)) {
                NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED
            } else {
                NativeProductSessionReconcileStatus.RUNTIME_UNAVAILABLE
            },
        )
    }

    private fun eventsAfter(
        lastSequence: Long?,
        events: List<BattleObservedEventView>,
    ): List<BattleObservedEventView>? {
        if (events.zipWithNext().any { (before, after) -> after.sequence != before.sequence + 1L }) return null
        if (lastSequence == null) {
            if (events.firstOrNull()?.sequence?.let { it != 1L } == true) return null
            return events
        }
        if (events.isEmpty()) return null
        val first = events.first().sequence
        val last = events.last().sequence
        if (last < lastSequence || first > lastSequence + 1L) return null
        if (lastSequence in first..last && events.none { it.sequence == lastSequence }) return null
        return events.filter { it.sequence > lastSequence }
    }

    private fun deadlineReached(deadlineNanos: Long): Boolean = nanoTime() - deadlineNanos >= 0L

    private fun validateDeferredActions(
        world: NativeProductSessionWorld,
        root: NativeBattleFrame,
        events: List<BattleObservedEventView>,
    ): DeferredValidation {
        val consumed = linkedSetOf<Long>()
        val consumedSides = linkedSetOf<BattleSide>()
        val issues = mutableListOf<NativeObservedTurnActionIssue>()
        listOf(
            BattleSide.ALLY to world.deferredAllyAction,
            BattleSide.OPPONENT to world.deferredOpponentAction,
        ).forEach { (side, action) ->
            if (action == null) return@forEach
            val evidence = evidenceForAction(side, root, action, events)
            if (evidence.isEmpty()) return@forEach
            val match = NativeObservedTurnActionMatcher.match(
                world.publicContext.state.format,
                side,
                root,
                listOf(action),
                evidence,
            )
            if (match.issues.isNotEmpty()) {
                issues += match.issues
            } else {
                evidence.mapTo(consumed) { it.sequence }
                consumedSides += side
            }
        }
        return DeferredValidation(consumed, consumedSides, issues)
    }

    private fun evidenceForAction(
        side: BattleSide,
        frame: NativeBattleFrame,
        action: BattleActionCandidate,
        events: List<BattleObservedEventView>,
    ): List<BattleObservedEventView> {
        val sideIds = when (side) {
            BattleSide.ALLY -> frame.p1Team
            BattleSide.OPPONENT -> frame.p2Team
        }.mapTo(linkedSetOf()) { java.util.UUID.fromString(it.uuid) }
        val components = if (action.kind == BattleActionKind.COMPOSITE) {
            action.componentActions
        } else {
            listOf(action)
        }
        val bySlot = components.mapNotNull { component ->
            component.actorSlot?.let { it to component }
        }.toMap()
        return events.filter { event ->
            if (event.actorPokemonId !in sideIds) return@filter false
            val component = event.actorSlot?.let(bySlot::get)
            when (event.kind) {
                BattleObservedEventKind.MOVE_USED -> component?.kind == BattleActionKind.USE_MOVE
                BattleObservedEventKind.TERA_TYPE_REVEALED -> component?.kind == BattleActionKind.USE_MOVE
                BattleObservedEventKind.SWITCHED -> component?.kind == BattleActionKind.SWITCH
                else -> false
            }
        }
    }

    private fun deferredForNextRequest(
        side: BattleSide,
        root: NativeBattleFrame,
        next: NativeBattleFrame,
        existing: BattleActionCandidate?,
        submitted: BattleActionCandidate,
        events: List<BattleObservedEventView>,
    ): BattleActionCandidate? {
        if (!isWaitRequest(side, next)) return null
        if (existing != null) return existing
        if (submitted.kind == BattleActionKind.WAIT) return null
        return submitted.takeIf { evidenceForAction(side, root, submitted, events).isEmpty() }
    }

    private fun isWaitRequest(side: BattleSide, frame: NativeBattleFrame): Boolean {
        if (frame.ended) return false
        val actions = NativeShowdownRequestActionFactory.actions(side, frame)
        return actions.size == 1 && actions.single().kind == BattleActionKind.WAIT
    }

    private fun failure(
        status: NativeProductSessionReconcileStatus,
        failedWorldId: String? = null,
        rootIssues: List<NativeBattleRootIssue> = emptyList(),
        observedActionIssues: List<NativeObservedTurnActionIssue> = emptyList(),
        failure: Throwable? = null,
    ) = NativeProductSessionReconciliation(
        status = status,
        failedWorldId = failedWorldId,
        rootIssues = rootIssues,
        observedActionIssues = observedActionIssues,
        failure = failure,
    )

    private data class Descendant(
        val world: NativeProductSessionWorld,
        val frame: NativeBattleFrame,
        val probability: Double,
        val split: Boolean,
        val deferredAllyAction: BattleActionCandidate?,
        val deferredOpponentAction: BattleActionCandidate?,
    ) {
        val identity: String = listOf(
            frame.snapshotJson,
            deferredAllyAction?.actionId.orEmpty(),
            deferredOpponentAction?.actionId.orEmpty(),
        ).joinToString("|")
    }

    private data class CompatibleFrame(
        val frame: NativeBattleFrame,
        val deferredAllyAction: BattleActionCandidate?,
        val deferredOpponentAction: BattleActionCandidate?,
    ) {
        val identity = DescendantIdentity(
            frame.snapshotJson,
            deferredAllyAction?.actionId,
            deferredOpponentAction?.actionId,
        )
    }

    private data class DescendantIdentity(
        val snapshotJson: String,
        val deferredAllyActionId: String?,
        val deferredOpponentActionId: String?,
    )

    private data class DeferredValidation(
        val consumedSequences: Set<Long>,
        val consumedSides: Set<BattleSide>,
        val issues: List<NativeObservedTurnActionIssue>,
    )

    private companion object {
        val WORLD_ORDER = compareBy<NativeProductSessionWorld> { it.key.hypothesisId }
            .thenBy { it.key.randomSampleIndex }
            .thenBy { it.key.lineage }
        val STRUCTURAL_ROOT_ISSUES = setOf(
            NativeBattleRootIssueCode.MALFORMED_FRAME,
            NativeBattleRootIssueCode.FORMAT_MISMATCH,
            NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH,
            NativeBattleRootIssueCode.OPENING_STATE_MISMATCH,
            NativeBattleRootIssueCode.ACTIVE_VIEW_MISMATCH,
            NativeBattleRootIssueCode.SIDE_MISMATCH,
            NativeBattleRootIssueCode.LEVEL_MISMATCH,
        )

        fun extendLineage(previous: String, snapshotJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(snapshotJson.toByteArray(Charsets.UTF_8))
            val suffix = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return if (previous.isBlank()) suffix else "$previous/$suffix"
        }
    }
}
