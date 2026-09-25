package jbro.cobblemon.morebattlecontent.betterai.search

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageObservationConditioner
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageObservationStatus
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory

internal data class NativeDeferredCommandState(
    val allyAction: BattleActionCandidate? = null,
    val opponentAction: BattleActionCandidate? = null,
) {
    fun action(side: BattleSide): BattleActionCandidate? = when (side) {
        BattleSide.ALLY -> allyAction
        BattleSide.OPPONENT -> opponentAction
    }

    fun withAction(side: BattleSide, action: BattleActionCandidate?): NativeDeferredCommandState = when (side) {
        BattleSide.ALLY -> copy(allyAction = action)
        BattleSide.OPPONENT -> copy(opponentAction = action)
    }
}

internal data class NativeDeferredCondition(
    val commands: NativeDeferredCommandState,
    val remainingEvents: List<BattleObservedEventView>,
    val issues: List<NativeObservedTurnActionIssue> = emptyList(),
)

internal enum class NativeIntermediateReplayStatus {
    AVAILABLE,
    DEADLINE_EXHAUSTED,
    ROOT_STATE_INCONSISTENT,
    OBSERVED_ACTION_MISMATCH,
    NO_CONSISTENT_WORLD,
}

internal data class NativeIntermediateReplayFrame(
    val frame: NativeBattleFrame,
    val deferredCommands: NativeDeferredCommandState,
    val observationLikelihood: Double = 1.0,
) {
    init {
        require(observationLikelihood.isFinite() && observationLikelihood > 0.0 && observationLikelihood <= 1.0)
    }
}

internal data class NativeIntermediateReplayResult(
    val status: NativeIntermediateReplayStatus,
    val frames: List<NativeIntermediateReplayFrame> = emptyList(),
    val rootIssues: List<NativeBattleRootIssue> = emptyList(),
    val observedActionIssues: List<NativeObservedTurnActionIssue> = emptyList(),
) {
    init {
        require((status == NativeIntermediateReplayStatus.AVAILABLE) == frames.isNotEmpty())
    }
}

/**
 * Replays native requests that occur while the ally has no new decision to make.
 *
 * A fast opposing pivot can open an opponent-only replacement request after both trainers have
 * already submitted their turn commands. Cobblemon does not call this Brain for that request, so
 * the retained native root must consume the public replacement and any delayed ally command before
 * it can be compared with the next ally decision. This class never invents an ally choice: it may
 * advance only while the complete ally-side request is `wait`.
 */
internal class NativeIntermediateRequestReplayer(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun conditionDeferred(
        format: BattleFormat,
        frame: NativeBattleFrame,
        commands: NativeDeferredCommandState,
        events: List<BattleObservedEventView>,
    ): NativeDeferredCondition {
        var remaining = events
        var conditioned = commands
        val issues = mutableListOf<NativeObservedTurnActionIssue>()
        BattleSide.entries.forEach { side ->
            val action = conditioned.action(side) ?: return@forEach
            val consumed = consumeEvidence(format, side, frame, action, remaining)
            if (consumed.issues.isNotEmpty()) {
                issues += consumed.issues
            } else if (consumed.matchedEvidence) {
                remaining = consumed.remainingEvents
                conditioned = conditioned.withAction(side, null)
            }
        }
        return NativeDeferredCondition(conditioned, remaining, issues)
    }

    fun replayAfterChoice(
        worker: NativeBranchWorker,
        definition: NativeBattleDefinition,
        format: BattleFormat,
        before: NativeBattleFrame,
        after: NativeBattleFrame,
        existingDeferred: NativeDeferredCommandState,
        submittedAllyAction: BattleActionCandidate,
        submittedOpponentAction: BattleActionCandidate,
        events: List<BattleObservedEventView>,
        publicState: BattleStateView,
        deadlineNanos: Long,
    ): NativeIntermediateReplayResult {
        val p1Choice = NativeShowdownChoiceEncoder.encode(submittedAllyAction, BattleSide.ALLY, before)
        val p2Choice = NativeShowdownChoiceEncoder.encode(submittedOpponentAction, BattleSide.OPPONENT, before)
        val damage = conditionDamageBranch(worker, before, after, p1Choice, p2Choice, events)
            ?: return noConsistentWorld()
        val eventsAfterDamage = events.filterNot { it.sequence in damage.explainedEventSequences }
        val transitioned = transition(
            format = format,
            before = before,
            after = damage.frame,
            existingDeferred = existingDeferred,
            submitted = mapOf(
                BattleSide.ALLY to submittedAllyAction,
                BattleSide.OPPONENT to submittedOpponentAction,
            ),
            events = eventsAfterDamage,
        )
        if (transitioned.issues.isNotEmpty()) {
            return observedMismatch(transitioned.issues)
        }
        return advance(
            worker = worker,
            definition = definition,
            format = format,
            state = ReplayState(
                damage.frame,
                transitioned.commands,
                transitioned.remainingEvents,
                damage.likelihood,
            ),
            publicState = publicState,
            deadlineNanos = deadlineNanos,
            intermediateDepth = 0,
        )
    }

    private fun advance(
        worker: NativeBranchWorker,
        definition: NativeBattleDefinition,
        format: BattleFormat,
        state: ReplayState,
        publicState: BattleStateView,
        deadlineNanos: Long,
        intermediateDepth: Int,
    ): NativeIntermediateReplayResult {
        if (deadlineReached(deadlineNanos)) return deadlineExhausted()
        val conditioned = conditionDeferred(format, state.frame, state.deferredCommands, state.remainingEvents)
        if (conditioned.issues.isNotEmpty()) return observedMismatch(conditioned.issues)

        val rootIssues = NativeBattleRootValidator.validate(definition, state.frame, publicState)
        val structural = rootIssues.filter { it.code in STRUCTURAL_ROOT_ISSUES }
        if (structural.isNotEmpty()) {
            return NativeIntermediateReplayResult(
                status = NativeIntermediateReplayStatus.ROOT_STATE_INCONSISTENT,
                rootIssues = structural,
            )
        }
        if (rootIssues.isEmpty()) {
            return NativeIntermediateReplayResult(
                status = NativeIntermediateReplayStatus.AVAILABLE,
                frames = listOf(NativeIntermediateReplayFrame(
                    state.frame,
                    conditioned.commands,
                    state.observationLikelihood,
                )),
            )
        }
        if (intermediateDepth >= MAX_INTERMEDIATE_REQUESTS) return noConsistentWorld()

        val allyActions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, state.frame)
        if (!allyActions.isWholeSideWait()) return noConsistentWorld()
        val opponentActions = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, state.frame)
        if (opponentActions.isEmpty() || opponentActions.isWholeSideWait()) return noConsistentWorld()
        val observed = NativeObservedTurnActionMatcher.match(
            format,
            BattleSide.OPPONENT,
            state.frame,
            opponentActions,
            conditioned.remainingEvents,
        )
        if (observed.issues.isNotEmpty()) return observedMismatch(observed.issues)

        val allyWait = allyActions.single()
        val compatible = linkedMapOf<ReplayIdentity, NativeIntermediateReplayFrame>()
        var firstMismatch: NativeIntermediateReplayResult? = null
        for (opponentAction in observed.actions) {
            if (deadlineReached(deadlineNanos)) return deadlineExhausted()
            val p1Choice = NativeShowdownChoiceEncoder.encode(allyWait, BattleSide.ALLY, state.frame)
            val p2Choice = NativeShowdownChoiceEncoder.encode(opponentAction, BattleSide.OPPONENT, state.frame)
            val next = worker.branchWithDamageEvidence(
                state.frame.snapshotJson,
                p1Choice,
                p2Choice,
            )
            val damage = conditionDamageBranch(
                worker,
                state.frame,
                next,
                p1Choice,
                p2Choice,
                conditioned.remainingEvents,
            ) ?: continue
            val eventsAfterDamage = conditioned.remainingEvents
                .filterNot { it.sequence in damage.explainedEventSequences }
            val transitioned = transition(
                format = format,
                before = state.frame,
                after = damage.frame,
                existingDeferred = conditioned.commands,
                submitted = mapOf(
                    BattleSide.ALLY to allyWait,
                    BattleSide.OPPONENT to opponentAction,
                ),
                events = eventsAfterDamage,
            )
            if (transitioned.issues.isNotEmpty()) {
                if (firstMismatch == null) firstMismatch = observedMismatch(transitioned.issues)
                continue
            }
            val advanced = advance(
                worker = worker,
                definition = definition,
                format = format,
                state = ReplayState(
                    damage.frame,
                    transitioned.commands,
                    transitioned.remainingEvents,
                    state.observationLikelihood * damage.likelihood,
                ),
                publicState = publicState,
                deadlineNanos = deadlineNanos,
                intermediateDepth = intermediateDepth + 1,
            )
            when (advanced.status) {
                NativeIntermediateReplayStatus.AVAILABLE -> advanced.frames.forEach { frame ->
                    compatible.putIfAbsent(frame.identity, frame)
                }
                NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED,
                NativeIntermediateReplayStatus.ROOT_STATE_INCONSISTENT,
                -> return advanced
                NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH -> {
                    if (firstMismatch == null) firstMismatch = advanced
                }
                NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD -> Unit
            }
        }
        return if (compatible.isNotEmpty()) {
            NativeIntermediateReplayResult(
                status = NativeIntermediateReplayStatus.AVAILABLE,
                frames = compatible.values.toList(),
            )
        } else {
            firstMismatch ?: noConsistentWorld()
        }
    }

    private fun transition(
        format: BattleFormat,
        before: NativeBattleFrame,
        after: NativeBattleFrame,
        existingDeferred: NativeDeferredCommandState,
        submitted: Map<BattleSide, BattleActionCandidate>,
        events: List<BattleObservedEventView>,
    ): NativeDeferredCondition {
        var commands = existingDeferred
        var remaining = events
        val issues = mutableListOf<NativeObservedTurnActionIssue>()
        BattleSide.entries.forEach { side ->
            val existing = commands.action(side)
            val submittedAction = requireNotNull(submitted[side])
            val nextWaits = isWholeSideWait(side, after)
            when {
                existing != null && nextWaits -> Unit
                existing != null -> {
                    val consumed = consumeEvidence(format, side, before, existing, remaining)
                    if (consumed.issues.isNotEmpty()) issues += consumed.issues
                    remaining = consumed.remainingEvents
                    commands = commands.withAction(side, null)
                }
                submittedAction.kind == BattleActionKind.WAIT -> Unit
                nextWaits -> commands = commands.withAction(side, submittedAction)
                else -> {
                    val consumed = consumeEvidence(format, side, before, submittedAction, remaining)
                    if (consumed.issues.isNotEmpty()) issues += consumed.issues
                    remaining = consumed.remainingEvents
                }
            }
        }
        return NativeDeferredCondition(commands, remaining, issues)
    }

    private fun consumeEvidence(
        format: BattleFormat,
        side: BattleSide,
        frame: NativeBattleFrame,
        action: BattleActionCandidate,
        events: List<BattleObservedEventView>,
    ): EvidenceConsumption {
        val evidence = evidenceForAction(side, frame, action, events)
        if (evidence.isEmpty()) return EvidenceConsumption(events, matchedEvidence = false)
        val match = NativeObservedTurnActionMatcher.match(format, side, frame, listOf(action), evidence)
        if (match.issues.isNotEmpty()) return EvidenceConsumption(events, false, match.issues)
        val sequences = evidence.mapTo(hashSetOf()) { it.sequence }
        return EvidenceConsumption(events.filterNot { it.sequence in sequences }, matchedEvidence = true)
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
        }.mapTo(linkedSetOf()) { UUID.fromString(it.uuid) }
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

    private fun isWholeSideWait(side: BattleSide, frame: NativeBattleFrame): Boolean =
        NativeShowdownRequestActionFactory.actions(side, frame).isWholeSideWait()

    private fun List<BattleActionCandidate>.isWholeSideWait(): Boolean =
        size == 1 && single().kind == BattleActionKind.WAIT && single().actorSlot == null

    private fun deadlineReached(deadlineNanos: Long): Boolean = nanoTime() - deadlineNanos >= 0L

    private fun conditionDamageBranch(
        worker: NativeBranchWorker,
        before: NativeBattleFrame,
        after: NativeBattleFrame,
        p1Choice: String,
        p2Choice: String,
        events: List<BattleObservedEventView>,
    ): ConditionedDamageBranch? {
        val newRolls = newDamageRolls(before, after)
        if (newRolls.isEmpty()) return ConditionedDamageBranch(after)
        val matchingEvents = events.filter { event ->
            event.kind == BattleObservedEventKind.HP_CHANGED &&
                event.hpFractionDelta?.let { it < 0.0 } == true &&
                event.precedingActionActorPokemonId != null &&
                event.actorPokemonId != null &&
                !event.precedingActionMoveId.isNullOrBlank() &&
                newRolls.any { roll ->
                    roll.turn == event.turn &&
                        UUID.fromString(roll.attackerPokemonUuid) == event.precedingActionActorPokemonId &&
                        UUID.fromString(roll.targetPokemonUuid) == event.actorPokemonId &&
                        nativeId(roll.moveId) == nativeId(requireNotNull(event.precedingActionMoveId))
                }
        }
        if (matchingEvents.isEmpty()) return ConditionedDamageBranch(after)
        val evidenceFrame = after.copy(executedDamageRolls = newRolls)
        val first = NativeDamageObservationConditioner.evaluate(evidenceFrame, matchingEvents)
        if (first.status == NativeDamageObservationStatus.CONTRADICTED) return null
        if (first.status != NativeDamageObservationStatus.CONSISTENT || first.forcedDamageRolls.isEmpty()) {
            return ConditionedDamageBranch(after)
        }
        val forced = worker.branchWithForcedDamage(
            before.snapshotJson,
            p1Choice,
            p2Choice,
            first.forcedDamageRolls,
        )
        val forcedRolls = newDamageRolls(before, forced)
        val confirmed = NativeDamageObservationConditioner.evaluate(
            forced.copy(executedDamageRolls = forcedRolls),
            matchingEvents,
        )
        if (confirmed.status != NativeDamageObservationStatus.CONSISTENT) return null
        return ConditionedDamageBranch(
            frame = forced,
            likelihood = confirmed.likelihood,
            explainedEventSequences = confirmed.explainedEventSequences,
        )
    }

    private fun newDamageRolls(
        @Suppress("UNUSED_PARAMETER") before: NativeBattleFrame,
        after: NativeBattleFrame,
    ): List<jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageRollFrame> =
        after.executedDamageRolls

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun observedMismatch(issues: List<NativeObservedTurnActionIssue>) = NativeIntermediateReplayResult(
        status = NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH,
        observedActionIssues = issues,
    )

    private fun deadlineExhausted() = NativeIntermediateReplayResult(
        status = NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED,
    )

    private fun noConsistentWorld() = NativeIntermediateReplayResult(
        status = NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD,
    )

    private data class ReplayState(
        val frame: NativeBattleFrame,
        val deferredCommands: NativeDeferredCommandState,
        val remainingEvents: List<BattleObservedEventView>,
        val observationLikelihood: Double,
    )

    private data class ConditionedDamageBranch(
        val frame: NativeBattleFrame,
        val likelihood: Double = 1.0,
        val explainedEventSequences: Set<Long> = emptySet(),
    ) {
        init {
            require(likelihood.isFinite() && likelihood > 0.0 && likelihood <= 1.0)
        }
    }

    private data class EvidenceConsumption(
        val remainingEvents: List<BattleObservedEventView>,
        val matchedEvidence: Boolean,
        val issues: List<NativeObservedTurnActionIssue> = emptyList(),
    )

    private data class ReplayIdentity(
        val snapshotJson: String,
        val observationLikelihood: Double,
        val deferredAllyActionId: String?,
        val deferredOpponentActionId: String?,
    )

    private val NativeIntermediateReplayFrame.identity: ReplayIdentity
        get() = ReplayIdentity(
            frame.snapshotJson,
            observationLikelihood,
            deferredCommands.allyAction?.actionId,
            deferredCommands.opponentAction?.actionId,
        )

    private companion object {
        const val MAX_INTERMEDIATE_REQUESTS = 8
        val STRUCTURAL_ROOT_ISSUES = setOf(
            NativeBattleRootIssueCode.MALFORMED_FRAME,
            NativeBattleRootIssueCode.FORMAT_MISMATCH,
            NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH,
            NativeBattleRootIssueCode.OPENING_STATE_MISMATCH,
            NativeBattleRootIssueCode.ACTIVE_VIEW_MISMATCH,
            NativeBattleRootIssueCode.SIDE_MISMATCH,
            NativeBattleRootIssueCode.LEVEL_MISMATCH,
        )
    }
}
