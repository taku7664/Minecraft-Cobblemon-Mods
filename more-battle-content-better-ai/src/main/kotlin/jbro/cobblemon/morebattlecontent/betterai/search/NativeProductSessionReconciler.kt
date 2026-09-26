package jbro.cobblemon.morebattlecontent.betterai.search

import java.security.MessageDigest
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedActionOrderConditioner
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedActionOrderStatus
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentMoveHypothesisRebinder
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
    private val intermediateReplayer = NativeIntermediateRequestReplayer(nanoTime)

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
                val currentPublicPokemonIds = currentContext.state.pokemon.mapTo(linkedSetOf()) {
                    it.battlePokemonId
                }
                for (world in session.worlds.sortedWith(WORLD_ORDER)) {
                    if (deadlineReached(deadlineNanos)) {
                        return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                    }
                    var root = world.rootSnapshot.frame
                    var definition = world.definition
                    var catalog = world.publicContext.publicActionCatalog
                    val deferred = intermediateReplayer.conditionDeferred(
                        session.format,
                        root,
                        NativeDeferredCommandState(
                            allyAction = world.deferredAllyAction,
                            opponentAction = world.deferredOpponentAction,
                        ),
                        eventWindow,
                    )
                    if (deferred.issues.isNotEmpty()) {
                        if (firstObservedMismatch == null) {
                            firstObservedMismatch = world.key.hypothesisId to deferred.issues
                        }
                        continue
                    }
                    val currentEvents = deferred.remainingEvents
                    val revealedMoves = currentEvents.asSequence()
                        .filter { it.kind == BattleObservedEventKind.MOVE_USED }
                        .filter { it.actorPokemonId != null && it.publicValueId != null }
                        .groupBy { requireNotNull(it.actorPokemonId) }
                        .mapValues { (_, events) -> events.mapTo(linkedSetOf()) { requireNotNull(it.publicValueId) } }
                    val preReplayPlan = NativeOpponentMoveHypothesisRebinder.plan(
                        definition = definition,
                        previousCatalog = catalog,
                        currentCatalog = currentContext.publicActionCatalog,
                        currentPublicPokemonIds = currentPublicPokemonIds,
                        revealedMoveIdsByPokemon = revealedMoves,
                    )
                    if (preReplayPlan.rebindings.isNotEmpty()) {
                        root = worker.rebindMoves(root.snapshotJson, preReplayPlan.rebindings)
                    }
                    definition = preReplayPlan.definition
                    catalog = preReplayPlan.catalog
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
                    val compatibleByAction = mutableListOf<List<CompatibleFrame>>()
                    for (opponentAction in observed.actions) {
                        if (deadlineReached(deadlineNanos)) {
                            return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                        }
                        val opponentChoice = NativeShowdownChoiceEncoder.encode(
                            opponentAction,
                            BattleSide.OPPONENT,
                            root,
                        )
                        val next = worker.branchWithDamageEvidence(root.snapshotJson, ownChoice, opponentChoice)
                        val replayed = intermediateReplayer.replayAfterChoice(
                            worker = worker,
                            definition = definition,
                            format = session.format,
                            before = root,
                            after = next,
                            existingDeferred = deferred.commands,
                            submittedAllyAction = ownNativeAction,
                            submittedOpponentAction = opponentAction,
                            events = currentEvents,
                            publicState = currentContext.state,
                            deadlineNanos = deadlineNanos,
                        )
                        val actionFrames = linkedMapOf<DescendantIdentity, CompatibleFrame>()
                        when (replayed.status) {
                            NativeIntermediateReplayStatus.AVAILABLE -> replayed.frames.forEach { frame ->
                                val order = NativeObservedActionOrderConditioner.evaluate(
                                    session.trainerTier,
                                    currentContext.state,
                                    frame.frame,
                                )
                                if (order.status == NativeObservedActionOrderStatus.CONTRADICTED) {
                                    return@forEach
                                }
                                val postReplayPlan = NativeOpponentMoveHypothesisRebinder.plan(
                                    definition = definition,
                                    previousCatalog = catalog,
                                    currentCatalog = currentContext.publicActionCatalog,
                                    currentPublicPokemonIds = currentPublicPokemonIds,
                                )
                                val updatedFrame = if (postReplayPlan.rebindings.isEmpty()) {
                                    frame.frame
                                } else {
                                    worker.rebindMoves(frame.frame.snapshotJson, postReplayPlan.rebindings)
                                }
                                val compatible = CompatibleFrame(
                                    frame = updatedFrame,
                                    definition = postReplayPlan.definition,
                                    catalog = postReplayPlan.catalog,
                                    observationLikelihood = frame.observationLikelihood,
                                    deferredAllyAction = frame.deferredCommands.allyAction,
                                    deferredOpponentAction = frame.deferredCommands.opponentAction,
                                )
                                val previous = actionFrames[compatible.identity]
                                actionFrames[compatible.identity] = if (previous == null) compatible else {
                                    compatible.copy(observationLikelihood =
                                        previous.observationLikelihood + compatible.observationLikelihood)
                                }
                            }
                            NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED -> return@lease failure(
                                NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED,
                            )
                            NativeIntermediateReplayStatus.ROOT_STATE_INCONSISTENT -> return@lease failure(
                                NativeProductSessionReconcileStatus.ROOT_STATE_INCONSISTENT,
                                failedWorldId = world.key.hypothesisId,
                                rootIssues = replayed.rootIssues,
                            )
                            NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH -> {
                                if (firstObservedMismatch == null) {
                                    firstObservedMismatch = world.key.hypothesisId to replayed.observedActionIssues
                                }
                            }
                            NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD -> Unit
                        }
                        if (actionFrames.isNotEmpty()) compatibleByAction += actionFrames.values.toList()
                    }
                    if (compatibleByAction.isEmpty()) continue

                    // No public action evidence distinguishes these descendants. Until a versioned
                    // opponent-policy likelihood exists, divide the prior across all publicly possible
                    // commands. Incompatible commands carry zero evidence likelihood; dividing only
                    // by survivors would erase that evidence against this build world.
                    // A single command may produce several exact HP states under one public percent;
                    // those states carry their native damage-roll likelihood, not another uniform split.
                    val probability = world.probability / observed.actions.size.toDouble()
                    val weightedFrames = linkedMapOf<DescendantIdentity, Pair<CompatibleFrame, Double>>()
                    compatibleByAction.forEach { frames -> frames.forEach { compatible ->
                        val mass = probability * compatible.observationLikelihood
                        val previous = weightedFrames[compatible.identity]
                        weightedFrames[compatible.identity] = compatible to (mass + (previous?.second ?: 0.0))
                    } }
                    weightedFrames.values.forEach { (compatible, mass) ->
                        descendants += Descendant(
                            world = world,
                            frame = compatible.frame,
                            definition = compatible.definition,
                            catalog = compatible.catalog,
                            probability = mass,
                            split = weightedFrames.size > 1,
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
                        definition = descendant.definition,
                        rootSnapshot = NativeProductRootSnapshot(session.rulesFingerprint, descendant.frame),
                        publicContext = currentContext.copy(
                            publicActionCatalog = descendant.catalog,
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
                        trainerTier = session.trainerTier,
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
        val definition: NativeBattleDefinition,
        val catalog: jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView,
        val probability: Double,
        val split: Boolean,
        val deferredAllyAction: BattleActionCandidate?,
        val deferredOpponentAction: BattleActionCandidate?,
    ) {
        val identity: String = listOf(
            frame.snapshotJson,
            probability.toString(),
            deferredAllyAction?.actionId.orEmpty(),
            deferredOpponentAction?.actionId.orEmpty(),
        ).joinToString("|")
    }

    private data class CompatibleFrame(
        val frame: NativeBattleFrame,
        val definition: NativeBattleDefinition,
        val catalog: jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView,
        val observationLikelihood: Double,
        val deferredAllyAction: BattleActionCandidate?,
        val deferredOpponentAction: BattleActionCandidate?,
    ) {
        init {
            require(observationLikelihood.isFinite() && observationLikelihood > 0.0 &&
                observationLikelihood <= 1.0)
        }

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

    private companion object {
        val WORLD_ORDER = compareBy<NativeProductSessionWorld> { it.key.hypothesisId }
            .thenBy { it.key.randomSampleIndex }
            .thenBy { it.key.lineage }

        fun extendLineage(previous: String, snapshotJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(snapshotJson.toByteArray(Charsets.UTF_8))
            val suffix = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return if (previous.isBlank()) suffix else "$previous/$suffix"
        }
    }
}
