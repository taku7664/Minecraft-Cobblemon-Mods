package jbro.cobblemon.mcc.betterai.search

import java.util.UUID

import java.security.MessageDigest
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedTurnActionIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedTurnActionIssueCode
import jbro.cobblemon.mcc.betterai.simulation.NativeRevealedPokemonBinder
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedActionOrderConditioner
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedActionOrderStatus
import jbro.cobblemon.mcc.betterai.simulation.NativeOpponentMoveHypothesisRebinder
import jbro.cobblemon.mcc.betterai.simulation.NativeRootActionMatcher
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRuntimeService

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
    /** When no world survived: why worlds died, with how many died that way. */
    val inconsistencies: Map<String, Int> = emptyMap(),
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
                val inconsistencies = sortedMapOf<String, Int>()
                val currentPublicPokemonIds = currentContext.state.pokemon.mapTo(linkedSetOf()) {
                    it.battlePokemonId
                }
                // Each world first replays the turn with its own random stream. When no world survives, the turn may
                // hinge on a chance outcome (a critical hit, a miss, a secondary effect) that stream did not draw, so
                // the worlds replay it again with other streams before the session is given up.
                val passes = if (worker.canReseed) 2 else 1
                // The replay may take only part of the decision clock: a search over the surviving worlds, or a rebuild
                // when none survive, needs the rest. When it runs out, the worlds already replayed carry on and the less
                // likely ones not reached yet are dropped, which is why the likeliest worlds are replayed first.
                val reconcileDeadline = nanoTime().let { now -> now + ((deadlineNanos - now) * RECONCILE_CLOCK_SHARE).toLong() }
                passes@ for (pass in 0 until passes) {
                    if (pass > 0 && descendants.isNotEmpty()) break
                    val salts: List<Int?> = if (pass == 0) listOf(null) else (1..CHANCE_RESAMPLES).toList()
                    // Other streams get a third of what is left, so a failure still leaves time to rebuild the worlds.
                    val passDeadline = if (pass == 0) reconcileDeadline else nanoTime().let { now -> now + (deadlineNanos - now) / 3 }
                    for (world in session.worlds.sortedWith(compareByDescending<NativeProductSessionWorld> { it.probability }.then(WORLD_ORDER))) {
                        if (deadlineReached(passDeadline)) {
                            if (pass > 0 || descendants.isNotEmpty()) break@passes
                            return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                        }
                        var root = world.rootSnapshot.frame
                        val publicTurnOffset = world.rootSnapshot.publicTurnOffset
                        val nativeEventWindow = if (publicTurnOffset == 0) eventWindow else
                            eventWindow.map { it.withNativeTurnOffset(publicTurnOffset) }
                        var definition = world.definition
                        var catalog = world.publicContext.publicActionCatalog
                        // An opponent sent out for the first time takes over its synthetic stand-in's ID.
                        val renames = NativeRevealedPokemonBinder.bind(
                            definition,
                            currentContext.state,
                            currentContext.opponentTeamPreview ?: world.publicContext.opponentTeamPreview,
                            eventWindow,
                            currentPublicPokemonIds,
                        )
                        if (renames == null) {
                            if (pass == 0) inconsistencies.merge("REVEALED_POKEMON_NOT_IN_WORLD", 1, Int::plus)
                            if (firstObservedMismatch == null) {
                                firstObservedMismatch = world.key.hypothesisId to listOf(NativeObservedTurnActionIssue(
                                    NativeObservedTurnActionIssueCode.REVEALED_POKEMON_NOT_IN_WORLD))
                            }
                            continue
                        }
                        if (renames.isNotEmpty()) {
                            root = worker.renamePokemon(root.snapshotJson,
                                renames.entries.associate { (from, to) -> from.toString() to to.toString() })
                            definition = NativeRevealedPokemonBinder.rename(definition, renames)
                            catalog = NativeRevealedPokemonBinder.rename(catalog, renames)
                        }
                        // The world keeps every unrevealed team member under its synthetic stand-in; the public battle
                        // only lists the revealed ones. Native frames are checked against the public state plus them.
                        val definitionIds = (definition.p1Team + definition.p2Team).mapTo(hashSetOf()) { UUID.fromString(it.uuid) }
                        val standIns = world.publicContext.state.pokemon.filter {
                            it.battlePokemonId in definitionIds && it.battlePokemonId !in currentPublicPokemonIds
                        }
                        val worldState = if (standIns.isEmpty()) currentContext.state
                            else currentContext.state.derive(pokemon = currentContext.state.pokemon + standIns)
                        val deferred = intermediateReplayer.conditionDeferred(
                            session.format,
                            root,
                            NativeDeferredCommandState(
                                allyAction = world.deferredAllyAction,
                                opponentAction = world.deferredOpponentAction,
                            ),
                            nativeEventWindow,
                        )
                        if (deferred.issues.isNotEmpty()) {
                            if (pass == 0) inconsistencies.merge(observedReason("deferred", deferred.issues), 1, Int::plus)
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
                        val ownNativeActions = NativeShowdownRequestActionFactory.actions(
                            BattleSide.ALLY, root, allowedMechanics = session.allowedMechanics)
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

                        val opponentNativeActions = NativeShowdownRequestActionFactory.actions(
                            BattleSide.OPPONENT, root, allowedMechanics = session.allowedMechanics)
                        val observed = NativeObservedTurnActionMatcher.match(
                            session.format,
                            BattleSide.OPPONENT,
                            root,
                            opponentNativeActions,
                            currentEvents,
                        )
                        if (observed.issues.isNotEmpty()) {
                            if (pass == 0) inconsistencies.merge(observedReason("root", observed.issues), 1, Int::plus)
                            if (firstObservedMismatch == null) {
                                firstObservedMismatch = world.key.hypothesisId to observed.issues
                            }
                            continue
                        }

                        val compatibleByAction = mutableListOf<List<CompatibleFrame>>()
                        var worldInconsistency: String? = null
                        for (opponentAction in observed.actions) {
                            val actionFrames = linkedMapOf<DescendantIdentity, CompatibleFrame>()
                            for (salt in salts) {
                                if (deadlineReached(passDeadline)) {
                                    if (pass > 0 || descendants.isNotEmpty()) break@passes
                                    return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                                }
                                val start = salt?.let { worker.reseed(root.snapshotJson, it) } ?: root
                                val ownChoice = NativeShowdownChoiceEncoder.encode(ownNativeAction, BattleSide.ALLY, start)
                                val opponentChoice = NativeShowdownChoiceEncoder.encode(
                                    opponentAction,
                                    BattleSide.OPPONENT,
                                    start,
                                )
                                val next = worker.branchWithDamageEvidence(start.snapshotJson, ownChoice, opponentChoice)
                                val replayed = intermediateReplayer.replayAfterChoice(
                                    worker = worker,
                                    definition = definition,
                                    format = session.format,
                                    before = start,
                                    after = next,
                                    existingDeferred = deferred.commands,
                                    submittedAllyAction = ownNativeAction,
                                    submittedOpponentAction = opponentAction,
                                    events = currentEvents,
                                    publicState = worldState,
                                    deadlineNanos = passDeadline,
                                    publicTurnOffset = publicTurnOffset,
                                )
                                when (replayed.status) {
                                    NativeIntermediateReplayStatus.AVAILABLE -> replayed.frames.forEach { frame ->
                                        val order = NativeObservedActionOrderConditioner.evaluate(
                                            session.trainerTier,
                                            worldState,
                                            frame.frame,
                                            publicTurnOffset,
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
                                    NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED -> {
                                        if (pass > 0 || descendants.isNotEmpty()) break@passes
                                        return@lease failure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
                                    }
                                    NativeIntermediateReplayStatus.ROOT_STATE_INCONSISTENT -> return@lease failure(
                                        NativeProductSessionReconcileStatus.ROOT_STATE_INCONSISTENT,
                                        failedWorldId = world.key.hypothesisId,
                                        rootIssues = replayed.rootIssues,
                                    )
                                    NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH -> {
                                        if (firstObservedMismatch == null) {
                                            firstObservedMismatch = world.key.hypothesisId to replayed.observedActionIssues
                                        }
                                        if (worldInconsistency == null) {
                                            worldInconsistency = observedReason("replay", replayed.observedActionIssues)
                                        }
                                    }
                                    NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD ->
                                        if (worldInconsistency == null) worldInconsistency = replayed.inconsistency ?: "UNKNOWN"
                                }
                                if (actionFrames.isEmpty() && replayed.status == NativeIntermediateReplayStatus.AVAILABLE &&
                                    worldInconsistency == null
                                ) {
                                    worldInconsistency = "ACTION_ORDER_CONTRADICTED"
                                }
                                if (actionFrames.isNotEmpty()) break
                            }
                            if (actionFrames.isNotEmpty()) compatibleByAction += actionFrames.values.toList()
                        }
                        if (compatibleByAction.isEmpty()) {
                            if (pass == 0) worldInconsistency?.let { inconsistencies.merge(it, 1, Int::plus) }
                            continue
                        }

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
                                publicState = worldState,
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
                }
                if (descendants.isEmpty()) {
                    val observedMismatch = firstObservedMismatch
                    return@lease if (observedMismatch != null) {
                        failure(
                            NativeProductSessionReconcileStatus.OBSERVED_ACTION_MISMATCH,
                            failedWorldId = observedMismatch.first,
                            observedActionIssues = observedMismatch.second,
                            inconsistencies = inconsistencies,
                        )
                    } else {
                        failure(NativeProductSessionReconcileStatus.NO_CONSISTENT_WORLD, inconsistencies = inconsistencies)
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
                        rootSnapshot = NativeProductRootSnapshot(
                            session.rulesFingerprint, descendant.frame, previous.rootSnapshot.publicTurnOffset),
                        publicContext = currentContext.copy(
                            state = descendant.publicState,
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
                        allowedMechanics = session.allowedMechanics,
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

    private fun observedReason(stage: String, issues: List<NativeObservedTurnActionIssue>): String =
        "$stage:" + issues.joinToString("+") { it.code.name + (it.detail?.let { detail -> " $detail" } ?: "") }

    private fun BattleObservedEventView.withNativeTurnOffset(offset: Int) = BattleObservedEventView(
        sequence = sequence,
        turn = turn + offset,
        kind = kind,
        actorPokemonId = actorPokemonId,
        targetPokemonIds = targetPokemonIds,
        publicValueId = publicValueId,
        hpFractionDelta = hpFractionDelta,
        baseMovePriority = baseMovePriority,
        precedingActionSequence = precedingActionSequence,
        precedingActionActorPokemonId = precedingActionActorPokemonId,
        precedingActionMoveId = precedingActionMoveId,
        publicSourceEffectId = publicSourceEffectId,
        moveOutcome = moveOutcome,
        actorSlot = actorSlot,
    )

    private fun failure(
        status: NativeProductSessionReconcileStatus,
        failedWorldId: String? = null,
        rootIssues: List<NativeBattleRootIssue> = emptyList(),
        observedActionIssues: List<NativeObservedTurnActionIssue> = emptyList(),
        failure: Throwable? = null,
        inconsistencies: Map<String, Int> = emptyMap(),
    ) = NativeProductSessionReconciliation(
        status = status,
        failedWorldId = failedWorldId,
        rootIssues = rootIssues,
        observedActionIssues = observedActionIssues,
        failure = failure,
        inconsistencies = inconsistencies,
    )

    private data class Descendant(
        val world: NativeProductSessionWorld,
        val publicState: jbro.cobblemon.mcc.internal.ai.BattleStateView,
        val frame: NativeBattleFrame,
        val definition: NativeBattleDefinition,
        val catalog: jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView,
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
        val catalog: jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView,
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
        /** Other random streams each world tries when no world explains the observed turn with its own. */
        const val CHANCE_RESAMPLES = 8
        const val RECONCILE_CLOCK_SHARE = 0.5

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
