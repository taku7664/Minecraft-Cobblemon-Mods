package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveOutcomeKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootIssueCode
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeDamageObservationConditioner
import jbro.cobblemon.mcc.betterai.simulation.NativeDamageObservationStatus
import jbro.cobblemon.mcc.betterai.simulation.NativeDamageRollFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeStatChange
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedTurnActionIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory

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
    /** Why no frame survived, for logs: the first contradiction this replay met. */
    val inconsistency: String? = null,
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
/** One stat correction a replay found, shared with the other worlds of the same reconciliation that start there. */
internal data class NativeStatFitKey(
    val pokemonUuid: String,
    val stat: String,
    val from: Int,
    val observationSequence: Long,
)

internal class NativeIntermediateRequestReplayer(
    private val nanoTime: () -> Long = System::nanoTime,
    private val fitHiddenStats: Boolean = true,
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
        publicTurnOffset: Int = 0,
        statFits: MutableMap<NativeStatFitKey, Int> = HashMap(),
    ): NativeIntermediateReplayResult {
        val p1Choice = NativeShowdownChoiceEncoder.encode(submittedAllyAction, BattleSide.ALLY, before)
        val p2Choice = NativeShowdownChoiceEncoder.encode(submittedOpponentAction, BattleSide.OPPONENT, before)
        val damageBranches = conditionDamageBranches(worker, before, after, p1Choice, p2Choice,
            events, deadlineNanos, statFits) ?: return deadlineExhausted()
        val compatible = linkedMapOf<ReplayIdentity, NativeIntermediateReplayFrame>()
        var firstMismatch: NativeIntermediateReplayResult? = null
        var firstInconsistency: String? = null
        var anyDamageBranch = false
        for (damage in damageBranches) {
            anyDamageBranch = true
            if (deadlineReached(deadlineNanos)) return deadlineExhausted()
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
                    damage.likelihood,
                ),
                publicState = publicState,
                deadlineNanos = deadlineNanos,
                intermediateDepth = 0,
                publicTurnOffset = publicTurnOffset,
                statFits = statFits,
            )
            when (advanced.status) {
                NativeIntermediateReplayStatus.AVAILABLE -> {
                    advanced.frames.forEach { compatible.merge(it) }
                    // The other fitting rolls differ only inside the public rounding: one world carries on.
                    break
                }
                NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED,
                NativeIntermediateReplayStatus.ROOT_STATE_INCONSISTENT,
                -> return advanced
                NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH -> {
                    if (firstMismatch == null) firstMismatch = advanced
                }
                NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD ->
                    if (firstInconsistency == null) firstInconsistency = advanced.inconsistency
            }
        }
        if (compatible.isEmpty() && deadlineReached(deadlineNanos)) return deadlineExhausted()
        if (!anyDamageBranch) return noConsistentWorld("DAMAGE_CONTRADICTED")
        return if (compatible.isNotEmpty()) {
            NativeIntermediateReplayResult(NativeIntermediateReplayStatus.AVAILABLE, compatible.values.toList())
        } else {
            firstMismatch ?: noConsistentWorld(firstInconsistency)
        }
    }

    private fun advance(
        worker: NativeBranchWorker,
        definition: NativeBattleDefinition,
        format: BattleFormat,
        state: ReplayState,
        publicState: BattleStateView,
        deadlineNanos: Long,
        intermediateDepth: Int,
        publicTurnOffset: Int,
        statFits: MutableMap<NativeStatFitKey, Int>,
    ): NativeIntermediateReplayResult {
        if (deadlineReached(deadlineNanos)) return deadlineExhausted()
        val conditioned = conditionDeferred(format, state.frame, state.deferredCommands, state.remainingEvents)
        if (conditioned.issues.isNotEmpty()) return observedMismatch(conditioned.issues)

        val rootIssues = NativeBattleRootValidator.validate(definition, state.frame, publicState, publicTurnOffset)
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
        val rootMismatch = describe(definition, rootIssues, state.frame, publicState)
        if (intermediateDepth >= MAX_INTERMEDIATE_REQUESTS) return noConsistentWorld("INTERMEDIATE_LIMIT:$rootMismatch")

        val allyActions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, state.frame)
        if (!allyActions.isWholeSideWait()) return noConsistentWorld(rootMismatch)
        val opponentActions = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, state.frame)
        if (opponentActions.isEmpty() || opponentActions.isWholeSideWait()) return noConsistentWorld(rootMismatch)
        val observed = NativeObservedTurnActionMatcher.match(
            format,
            BattleSide.OPPONENT,
            state.frame,
            opponentActions,
            conditioned.remainingEvents,
        )
        if (observed.issues.isNotEmpty()) return observedMismatch(observed.issues)

        val allyWait = allyActions.single()
        val compatibleByAction = mutableListOf<List<NativeIntermediateReplayFrame>>()
        var firstMismatch: NativeIntermediateReplayResult? = null
        var firstInconsistency: String? = null
        for (opponentAction in observed.actions) {
            if (deadlineReached(deadlineNanos)) return deadlineExhausted()
            val p1Choice = NativeShowdownChoiceEncoder.encode(allyWait, BattleSide.ALLY, state.frame)
            val p2Choice = NativeShowdownChoiceEncoder.encode(opponentAction, BattleSide.OPPONENT, state.frame)
            val next = worker.branchWithDamageEvidence(
                state.frame.snapshotJson,
                p1Choice,
                p2Choice,
            )
            val damageBranches = conditionDamageBranches(
                worker,
                state.frame,
                next,
                p1Choice,
                p2Choice,
                conditioned.remainingEvents,
                deadlineNanos,
                statFits,
            ) ?: return deadlineExhausted()
            val actionFrames = linkedMapOf<ReplayIdentity, NativeIntermediateReplayFrame>()
            var anyDamageBranch = false
            for (damage in damageBranches) {
                anyDamageBranch = true
                if (deadlineReached(deadlineNanos)) return deadlineExhausted()
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
                    publicTurnOffset = publicTurnOffset,
                    statFits = statFits,
                )
                when (advanced.status) {
                    NativeIntermediateReplayStatus.AVAILABLE -> {
                        advanced.frames.forEach { actionFrames.merge(it) }
                        break
                    }
                    NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED,
                    NativeIntermediateReplayStatus.ROOT_STATE_INCONSISTENT,
                    -> return advanced
                    NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH -> {
                        if (firstMismatch == null) firstMismatch = advanced
                    }
                    NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD ->
                        if (firstInconsistency == null) firstInconsistency = advanced.inconsistency
                }
            }
            if (actionFrames.isEmpty() && deadlineReached(deadlineNanos)) return deadlineExhausted()
            if (!anyDamageBranch && firstInconsistency == null) firstInconsistency = "DAMAGE_CONTRADICTED"
            if (actionFrames.isNotEmpty()) compatibleByAction += actionFrames.values.toList()
        }
        return if (compatibleByAction.isNotEmpty()) {
            val compatible = linkedMapOf<ReplayIdentity, NativeIntermediateReplayFrame>()
            compatibleByAction.forEach { frames ->
                frames.forEach { frame ->
                    compatible.merge(frame.copy(
                        observationLikelihood = frame.observationLikelihood / observed.actions.size,
                    ))
                }
            }
            NativeIntermediateReplayResult(
                status = NativeIntermediateReplayStatus.AVAILABLE,
                frames = compatible.values.toList(),
            )
        } else {
            firstMismatch ?: noConsistentWorld(firstInconsistency)
        }
    }

    private fun describe(
        definition: NativeBattleDefinition,
        issues: List<NativeBattleRootIssue>,
        frame: NativeBattleFrame,
        publicState: BattleStateView,
    ): String {
        val species = (definition.p1Team + definition.p2Team).associate { it.uuid to it.species }
        val nativeById = (frame.p1Team + frame.p2Team).associateBy { it.uuid }
        val publicById = publicState.pokemon.associateBy { it.battlePokemonId.toString() }
        return issues.joinToString("+") { issue ->
            val id = issue.battlePokemonId?.toString()
            val native = id?.let(nativeById::get)
            val public = id?.let(publicById::get)
            val hp = if (issue.code == NativeBattleRootIssueCode.HP_MISMATCH && native != null && public != null) {
                "[%s native=%d/%d public=%.3f]".format(Locale.ROOT, public.side.name.take(1), native.hp, native.maxHp, public.hpFraction)
            } else ""
            issue.code.name + (id?.let { "@" + (species[it] ?: it.take(8)) } ?: "") + hp
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

    private fun conditionDamageBranches(
        worker: NativeBranchWorker,
        before: NativeBattleFrame,
        after: NativeBattleFrame,
        p1Choice: String,
        p2Choice: String,
        events: List<BattleObservedEventView>,
        deadlineNanos: Long,
        statFits: MutableMap<NativeStatFitKey, Int>,
    ): Sequence<ConditionedDamageBranch>? {
        val newRolls = newDamageRolls(before, after)
        if (newRolls.isEmpty()) return sequenceOf(ConditionedDamageBranch(after))
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
        if (matchingEvents.isEmpty()) return sequenceOf(ConditionedDamageBranch(after))
        val evidenceFrame = after.copy(executedDamageRolls = newRolls)
        var first = NativeDamageObservationConditioner.evaluate(evidenceFrame, matchingEvents)
        var root = before
        var unforced = after
        var weight = 1.0
        if (first.status == NativeDamageObservationStatus.CONTRADICTED) {
            val fit = fitHiddenStats(worker, before, evidenceFrame, p1Choice, p2Choice, matchingEvents, events, deadlineNanos,
                statFits)
                ?: return if (deadlineReached(deadlineNanos)) null else emptySequence()
            root = fit.root
            unforced = fit.after
            first = fit.conditioning
            weight = fit.weight
        }
        if (first.status != NativeDamageObservationStatus.CONSISTENT || first.forcedDamageRollOptions.isEmpty()) {
            return sequenceOf(ConditionedDamageBranch(unforced, likelihood = weight))
        }
        // The rolls that fit one public loss differ only in exact HP inside its rounding. Keeping a world per roll
        // multiplied the worlds every turn until a decision could neither replay nor search them, so the replay
        // takes the first that the rest of the turn and the board accept, middle rolls first, with the whole
        // likelihood. They are replayed lazily, so the usual one costs one replay.
        val ordered = first.forcedDamageRollOptions.map { options ->
            options.withIndex().sortedBy { (index, _) -> abs(2 * index - (options.size - 1)) }.map { it.value }
        }
        val combinations = ordered.fold(sequenceOf(emptyList<jbro.cobblemon.mcc.betterai.simulation.NativeForcedDamageRoll>())) {
            previous, options -> previous.flatMap { chosen -> options.asSequence().map { chosen + it } }
        }
        val conditioning = first
        return combinations.take(MAX_DAMAGE_BRANCH_ATTEMPTS).mapNotNull { forcedRolls ->
            if (deadlineReached(deadlineNanos)) return@mapNotNull null
            val forced = worker.branchWithForcedDamage(
                root.snapshotJson,
                p1Choice,
                p2Choice,
                forcedRolls,
            )
            val confirmed = NativeDamageObservationConditioner.evaluate(
                forced.copy(executedDamageRolls = newDamageRolls(root, forced)),
                matchingEvents,
                requireActualRollMatch = true,
            )
            if (confirmed.status != NativeDamageObservationStatus.CONSISTENT) return@mapNotNull null
            ConditionedDamageBranch(
                frame = forced,
                likelihood = conditioning.likelihood * weight,
                explainedEventSequences = confirmed.explainedEventSequences,
            )
        }
    }

    private class StatFit(
        val root: NativeBattleFrame,
        val after: NativeBattleFrame,
        val conditioning: jbro.cobblemon.mcc.betterai.simulation.NativeDamageObservationConditioning,
        val weight: Double,
    )

    /**
     * Explains damage no roll of this world reaches by the opponent's hidden stat instead of dropping the world.
     *
     * The world's spread is one of a few usage builds, while a real opponent's stats can be anything its species
     * and level allow, so a hit can miss every roll in every world. The opponent's stat that the calculation read
     * (attack when it attacked, defense when it was hit) is scaled by the observed over the expected damage, set
     * in the battle, and the turn replayed until the rolls reach what was seen. A value past any legal spread
     * stands for an unseen item or ability and keeps a small weight. A critical hit the world drew differently is
     * left to the chance resampling, and a hit between two known stats is not fitted.
     */
    private fun fitHiddenStats(
        worker: NativeBranchWorker,
        before: NativeBattleFrame,
        evidence: NativeBattleFrame,
        p1Choice: String,
        p2Choice: String,
        observations: List<BattleObservedEventView>,
        events: List<BattleObservedEventView>,
        deadlineNanos: Long,
        statFits: MutableMap<NativeStatFitKey, Int>,
    ): StatFit? {
        if (!fitHiddenStats || !worker.canRestat) return null
        val opponents = before.p2Team.mapTo(hashSetOf()) { it.uuid }
        var root = before
        var current = evidence
        var weight = 1.0
        val started = linkedMapOf<NativeStatFitKey, NativeStatChange>()
        repeat(MAX_STAT_FITS) {
            if (deadlineReached(deadlineNanos)) return null
            val (observation, roll) = firstContradiction(current.executedDamageRolls, observations) ?: return null
            if (roll.critical == null || roll.critical != observedCritical(events, observation)) return null
            val offense = roll.offense ?: return null
            val defense = roll.defense ?: return null
            val (stat, raisesDamage) = when {
                offense.pokemonUuid in opponents && defense.pokemonUuid !in opponents -> offense to true
                defense.pokemonUuid in opponents && offense.pokemonUuid !in opponents -> defense to false
                else -> return null
            }
            val value = (root.p1Team + root.p2Team).firstOrNull { it.uuid == stat.pokemonUuid }?.stats?.get(stat.stat)
                ?: return null
            val knockedOut = events.any {
                it.kind == BattleObservedEventKind.FAINTED && it.turn == observation.turn &&
                    it.actorPokemonId == observation.actorPokemonId
            }
            // A knockout only says the hit reached the remaining HP; otherwise aim the middle roll at the loss.
            val expected = if (knockedOut) roll.possibleHpLosses.max().toDouble() else roll.possibleHpLosses.average()
            val wanted = if (knockedOut) roll.hpBefore.toDouble() else -requireNotNull(observation.hpFractionDelta) * roll.maxHp
            if (expected <= 0.0 || wanted <= 0.0) return null
            val ratio = wanted / expected
            val key = NativeStatFitKey(stat.pokemonUuid, stat.stat, value, observation.sequence)
            started.putIfAbsent(key, NativeStatChange(stat.pokemonUuid, stat.stat, value))
            var next = statFits[key] ?: if (raisesDamage) ceil(value * ratio).toInt() else floor(value / ratio).toInt()
            if (next == value) next += if ((ratio > 1.0) == raisesDamage) 1 else -1
            val legal = worker.statRange(root.snapshotJson, stat.pokemonUuid, stat.stat) ?: return null
            val effective = (legal.first / 2).coerceAtLeast(1)..(legal.last * 2)
            next = next.coerceIn(effective)
            if (next == value) return null
            if (next !in legal) weight = minOf(weight, EFFECTIVE_STAT_WEIGHT)
            root = worker.restat(root.snapshotJson, listOf(NativeStatChange(stat.pokemonUuid, stat.stat, next)))
            current = worker.branchWithDamageEvidence(root.snapshotJson, p1Choice, p2Choice)
            val conditioning = NativeDamageObservationConditioner.evaluate(current, observations)
            if (conditioning.status != NativeDamageObservationStatus.CONTRADICTED) {
                // The other worlds starting from the same stat on the same hit go straight to the found value.
                val found = (root.p1Team + root.p2Team).associate { it.uuid to it.stats }
                started.forEach { (startedKey, change) ->
                    found[change.pokemonUuid]?.get(change.stat)?.let { statFits[startedKey] = it }
                }
                return StatFit(root, current, conditioning, minOf(weight, FITTED_STAT_WEIGHT))
            }
        }
        return null
    }

    /** The first observed hit, in the conditioner's pairing, that no roll of [rolls] explains. */
    private fun firstContradiction(
        rolls: List<NativeDamageRollFrame>,
        observations: List<BattleObservedEventView>,
    ): Pair<BattleObservedEventView, NativeDamageRollFrame>? {
        val unused = rolls.toMutableList()
        observations.asSequence()
            .filter { it.precedingActionSequence != null && it.publicSourceEffectId == null }
            .sortedBy(BattleObservedEventView::sequence)
            .forEach { observation ->
                val index = unused.indexOfFirst { roll ->
                    roll.turn == observation.turn &&
                        UUID.fromString(roll.attackerPokemonUuid) == observation.precedingActionActorPokemonId &&
                        UUID.fromString(roll.targetPokemonUuid) == observation.actorPokemonId &&
                        nativeId(roll.moveId) == nativeId(requireNotNull(observation.precedingActionMoveId))
                }
                if (index < 0) return null
                val roll = unused.removeAt(index)
                if (!NativeDamageObservationConditioner.supports(roll, -requireNotNull(observation.hpFractionDelta))) {
                    return observation to roll
                }
            }
        return null
    }

    /** Whether the game announced a critical hit on this observation's target between its move and the loss. */
    private fun observedCritical(events: List<BattleObservedEventView>, observation: BattleObservedEventView): Boolean =
        events.any {
            it.kind == BattleObservedEventKind.MOVE_OUTCOME &&
                it.moveOutcome?.kind == BattleMoveOutcomeKind.CRITICAL_HIT &&
                it.turn == observation.turn &&
                observation.actorPokemonId in it.targetPokemonIds &&
                it.sequence > requireNotNull(observation.precedingActionSequence) && it.sequence < observation.sequence
        }

    private fun newDamageRolls(
        @Suppress("UNUSED_PARAMETER") before: NativeBattleFrame,
        after: NativeBattleFrame,
    ): List<jbro.cobblemon.mcc.betterai.simulation.NativeDamageRollFrame> =
        after.executedDamageRolls

    private fun nativeId(value: String): String = PublicIds.canonical(value)

    private fun observedMismatch(issues: List<NativeObservedTurnActionIssue>) = NativeIntermediateReplayResult(
        status = NativeIntermediateReplayStatus.OBSERVED_ACTION_MISMATCH,
        observedActionIssues = issues,
    )

    private fun deadlineExhausted() = NativeIntermediateReplayResult(
        status = NativeIntermediateReplayStatus.DEADLINE_EXHAUSTED,
    )

    private fun noConsistentWorld(inconsistency: String? = null) = NativeIntermediateReplayResult(
        status = NativeIntermediateReplayStatus.NO_CONSISTENT_WORLD,
        inconsistency = inconsistency,
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
        val deferredAllyActionId: String?,
        val deferredOpponentActionId: String?,
    )

    private fun MutableMap<ReplayIdentity, NativeIntermediateReplayFrame>.merge(frame: NativeIntermediateReplayFrame) {
        val prior = this[frame.identity]
        if (prior == null) {
            this[frame.identity] = frame
        } else {
            val combinedLikelihood = prior.observationLikelihood + frame.observationLikelihood
            require(combinedLikelihood <= 1.0 + 1e-12) {
                "Native replay merged more than one unit of probability into one snapshot"
            }
            this[frame.identity] = prior.copy(observationLikelihood = combinedLikelihood.coerceAtMost(1.0))
        }
    }

    private val NativeIntermediateReplayFrame.identity: ReplayIdentity
        get() = ReplayIdentity(
            frame.snapshotJson,
            deferredCommands.allyAction?.actionId,
            deferredCommands.opponentAction?.actionId,
        )

    private companion object {
        const val MAX_INTERMEDIATE_REQUESTS = 8
        const val MAX_DAMAGE_BRANCH_ATTEMPTS = 8
        /** Stat corrections tried per observed turn; the scaling usually lands in one or two. */
        const val MAX_STAT_FITS = 4
        /** A world whose hidden stat had to be corrected, against one whose hypothesis explained the hit. */
        const val FITTED_STAT_WEIGHT = 0.5
        /** A correction past any legal spread: an unseen item or ability more likely than the stat itself. */
        const val EFFECTIVE_STAT_WEIGHT = 0.05
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
