package jbro.cobblemon.mcc.betterai.search

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView
import jbro.cobblemon.mcc.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.mcc.betterai.simulation.NativeRootActionMapping
import jbro.cobblemon.mcc.betterai.simulation.NativeRootActionMatcher
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeSearchPosition
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat

/** Identifies one public opponent hypothesis evaluated with one common random sample. */
internal data class NativeSearchWorldKey(
    val hypothesisId: String,
    val randomSampleIndex: Int,
    /** Distinguishes publicly indistinguishable native descendants of the same sampled world. */
    val lineage: String = "",
) {
    init {
        require(hypothesisId.isNotBlank())
        require(randomSampleIndex >= 0)
    }
}

internal enum class NativeSearchTerminationReason { COMPLETED, DEADLINE, NODE_BUDGET }

internal data class NativeRootActionValue(
    val action: BattleActionCandidate,
    val value: Double,
)

/** One root iteration that completed for every candidate before search stopped. */
internal data class NativeCompletedSearchDepth(
    val depth: Int,
    val rootValues: List<NativeRootActionValue>,
) {
    init {
        require(depth > 0)
        require(rootValues.map { it.action.actionId }.distinct().size == rootValues.size)
        require(rootValues.all { it.value.isFinite() })
    }
}

internal data class NativeRecursiveSearchResult(
    val rootValues: List<NativeRootActionValue>,
    val completedIterations: List<NativeCompletedSearchDepth>,
    val nodesVisited: Int,
    val depthCompleted: Int,
    val truncated: Boolean,
    val terminationReason: NativeSearchTerminationReason,
) {
    init {
        require(nodesVisited >= 0)
        require(depthCompleted >= 0)
        require(completedIterations.map(NativeCompletedSearchDepth::depth) == (1..depthCompleted).toList()) {
            "Completed native iterations must be contiguous through depthCompleted"
        }
        require(rootValues == completedIterations.lastOrNull()?.rootValues.orEmpty()) {
            "Native root values must come from the last fully completed iteration"
        }
    }

    val bestAction: BattleActionCandidate? = rootValues.maxByOrNull(NativeRootActionValue::value)?.action
}

internal data class NativeProductSearchAttempt(
    val mapping: NativeRootActionMapping,
    val result: NativeRecursiveSearchResult?,
)

/** Iterative-deepening minimax whose state transitions come only from native Showdown snapshots. */
internal class NativeRecursiveSearch(
    private val tree: NativeShowdownSearchTree,
    private val world: NativeSearchWorldKey,
    private val evaluate: (BattleStateView) -> Double,
    private val nodeLimit: Int,
    private val responseMemory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
    private val responseInformation: Double = 1.0,
    private val allowSetupAttackExtension: Boolean = false,
    private val excludeFutureAllyVoluntarySwitches: Boolean = false,
    private val shouldContinue: () -> Boolean = { true },
    private val cacheEntryLimit: Int = DEFAULT_CACHE_ENTRY_LIMIT,
    /** AI-only threat multipliers; see [LocalOpponentThreat]. Never used to pick opponent replies. */
    private val opponentThreatWeights: Map<UUID, Double> = emptyMap(),
    private val tolerateExtraNativeRootActions: Boolean = false,
    /** See [LocalLookaheadBudget.opponentResponseLimit]. */
    private val opponentResponseLimit: Int? = null,
    /** See [LocalLookaheadBudget.finalPlyAttacksOnly]. */
    private val finalPlyAttacksOnly: Boolean = false,
) {
    private var nodesVisited = 0
    private var truncated = false
    private var terminationReason = NativeSearchTerminationReason.COMPLETED
    private var previousRootResponseValues: Map<String, Map<String, Double>> = emptyMap()
    private var attackOnlyFinalPly = false
    // A deterministic Showdown transition does not depend on the search horizon. Values do, so
    // only the value key carries depthRemaining. Snapshot keys can be large for full teams, so
    // both decision-local caches evict deterministically rather than retaining every visited node.
    private val branchCache = object : LinkedHashMap<BranchKey, NativeSearchPosition>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<BranchKey, NativeSearchPosition>?,
        ): Boolean = size > cacheEntryLimit
    }
    private val valueCache = object : LinkedHashMap<ValueKey, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ValueKey, Double>?): Boolean =
            size > cacheEntryLimit
    }

    init {
        require(nodeLimit > 0)
        require(responseInformation.isFinite() && responseInformation in 0.0..1.0)
        require(cacheEntryLimit > 0)
        require(opponentResponseLimit == null || opponentResponseLimit > 0)
    }

    fun evaluate(maxDepth: Int): NativeRecursiveSearchResult {
        return evaluateNative(maxDepth, tree.actions(tree.root, BattleSide.ALLY))
    }

    fun evaluateProduct(
        productActions: List<BattleActionCandidate>,
        maxDepth: Int,
    ): NativeProductSearchAttempt {
        val mapping = NativeRootActionMatcher.match(
            tree.root.state.format,
            productActions,
            tree.actions(tree.root, BattleSide.ALLY),
        )
        // A rebuilt mid-battle root does not carry every move restriction (Disable, Torment), so it may offer
        // more than the battle does; the product candidates are the legal ones, and every one must still map.
        val accepted = if (tolerateExtraNativeRootActions) {
            mapping.unmatchedProductActionIds.isEmpty() && mapping.ambiguousProductActionIds.isEmpty()
        } else {
            mapping.complete
        }
        if (!accepted) return NativeProductSearchAttempt(mapping, null)
        val nativeActions = productActions.map { product ->
            mapping.productToNative.getValue(product.actionId)
        }
        val nativeResult = evaluateNative(maxDepth, nativeActions)
        val productByNativeId = mapping.productToNative.entries.associate { (productId, native) ->
            native.actionId to productActions.single { it.actionId == productId }
        }
        return NativeProductSearchAttempt(
            mapping = mapping,
            result = nativeResult.copy(
                rootValues = restoreProductActions(nativeResult.rootValues, productByNativeId),
                completedIterations = nativeResult.completedIterations.map { iteration ->
                    iteration.copy(
                        rootValues = restoreProductActions(iteration.rootValues, productByNativeId),
                    )
                },
            ),
        )
    }

    private fun restoreProductActions(
        values: List<NativeRootActionValue>,
        productByNativeId: Map<String, BattleActionCandidate>,
    ): List<NativeRootActionValue> = values.map { rootValue ->
        NativeRootActionValue(
            action = requireNotNull(productByNativeId[rootValue.action.actionId]),
            value = rootValue.value,
        )
    }

    private fun evaluateNative(
        maxDepth: Int,
        rootActions: List<BattleActionCandidate>,
    ): NativeRecursiveSearchResult {
        require(maxDepth > 0)
        var accepted = emptyList<NativeRootActionValue>()
        val completedIterations = mutableListOf<NativeCompletedSearchDepth>()
        var completedDepth = 0
        var previousIterationNodes = 0
        var priorIterationNodes = 0
        val targetDepth = if (allowSetupAttackExtension && maxDepth == 2) 3 else maxDepth
        for (depth in 1..targetDepth) {
            if (depth == 3 && !admitAttackExtension(previousIterationNodes, priorIterationNodes)) break
            if (depth == 3 && allowSetupAttackExtension && maxDepth == 2) valueCache.clear()
            attackOnlyFinalPly = depth == 3 && allowSetupAttackExtension && maxDepth == 2
            val iteration = evaluateRootDepth(rootActions, depth)
            if (truncated || iteration == null) break
            accepted = iteration
            completedDepth = depth
            completedIterations += NativeCompletedSearchDepth(depth, iteration)
            priorIterationNodes = previousIterationNodes
            previousIterationNodes = nodesVisited
        }
        return NativeRecursiveSearchResult(
            rootValues = accepted,
            completedIterations = completedIterations,
            nodesVisited = nodesVisited,
            depthCompleted = completedDepth,
            truncated = truncated,
            terminationReason = terminationReason,
        )
    }

    private fun admitAttackExtension(depthTwoTotal: Int, depthOneTotal: Int): Boolean {
        val firstIncrement = depthOneTotal.coerceAtLeast(1)
        val secondIncrement = (depthTwoTotal - depthOneTotal).coerceAtLeast(1)
        val growth = (secondIncrement.toDouble() / firstIncrement).coerceAtLeast(2.0)
        val estimatedThirdIncrement = secondIncrement * growth * 1.5
        return estimatedThirdIncrement <= nodeLimit - nodesVisited && timeAvailable()
    }

    private fun evaluateRootDepth(
        rootActions: List<BattleActionCandidate>,
        depth: Int,
    ): List<NativeRootActionValue>? {
        val opponentActions = tree.actions(tree.root, BattleSide.OPPONENT)
        if (rootActions.isEmpty() || opponentActions.isEmpty()) return emptyList()
        val rootHpAdvantage = hpAdvantage(tree.root)
        val values = mutableListOf<NativeRootActionValue>()
        val currentResponseValues = linkedMapOf<String, Map<String, Double>>()
        val rootThreat = LocalOpponentThreat.materialAdjustment(tree.root.state, opponentThreatWeights)
        // A narrower tier follows only the replies the shallower iteration found worst for it on average over its own
        // choices. Every root action meets the same replies; a per-action set let a move whose own worst replies
        // happened to be mild outrank one that met the dangerous ones, so an immune Sludge Bomb beat Giga Drain.
        val followedResponseIds = if (depth > 1 && opponentResponseLimit != null) {
            opponentActions.sortedBy { response ->
                rootActions.map { previousRootResponseValues[it.actionId]?.get(response.actionId) ?: 0.0 }.average()
            }.take(opponentResponseLimit).map(BattleActionCandidate::actionId).toSet()
        } else null
        for (allyAction in rootActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            var worstThreatDelta = 0.0
            val responseValues = linkedMapOf<String, Double>()
            val orderedResponses = NativeOpponentResponseOrdering.order(
                opponentActions, responseMemory, responseInformation,
                previousRootResponseValues[allyAction.actionId].orEmpty(),
            )
            val followedResponses = if (followedResponseIds != null) {
                orderedResponses.filter { it.actionId in followedResponseIds }
            } else {
                orderedResponses
            }
            for (opponentAction in followedResponses) {
                val child = descend(tree.root, allyAction, opponentAction) ?: return null
                // The deep leaf can give the same final board to an immediate attack and a wasted
                // recovery turn. Retain first-turn HP progress as a separate tempo term. A
                // shallow search already sees that progress directly, so only deeper roots need it.
                // Knockout/living bonuses stay in the regular evaluation; adding them here again
                // would make a quick KO worth several extra health bars.
                val tempo = if (depth > 1) {
                    (hpAdvantage(child) - rootHpAdvantage) * ROOT_TEMPO_WEIGHT
                } else 0.0
                val value = projectedValue(child, depth - 1, worstResponse - tempo) ?: return null
                val rootValue = value + tempo
                responseValues[opponentAction.actionId] = rootValue
                if (rootValue < worstResponse) {
                    worstResponse = rootValue
                    worstThreatDelta = LocalOpponentThreat.materialAdjustment(child.state, opponentThreatWeights) - rootThreat
                }
            }
            currentResponseValues[allyAction.actionId] = responseValues
            // The opponent's reply was chosen by the plain value above; only the AI's own evaluation
            // of the outcome it leads to carries the threat priority.
            values += NativeRootActionValue(allyAction, worstResponse + worstThreatDelta)
        }
        previousRootResponseValues = currentResponseValues
        return values
    }

    private fun positionValue(
        position: NativeSearchPosition,
        depthRemaining: Int,
        upperBound: Double,
    ): Double? {
        if (!timeAvailable()) return null
        if (depthRemaining <= 0 || position.frame.ended) return leafValue(position)
        val key = ValueKey(
            tree.rulesFingerprint,
            world.hypothesisId,
            world.randomSampleIndex,
            position.frame.snapshotJson,
            depthRemaining,
        )
        valueCache[key]?.let { return it }
        // Root choices stay complete. Only voluntary switches in simulated continuation
        // requests are narrowed; forced/pivot replacements retain every legal target.
        val allyActions = if ((attackOnlyFinalPly || finalPlyAttacksOnly) && depthRemaining == 1) {
            tree.attackingActions(position)
        } else {
            tree.actions(position, BattleSide.ALLY,
                if (excludeFutureAllyVoluntarySwitches) 0 else FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT)
        }
        val opponentActions = NativeOpponentResponseOrdering.order(
            tree.actions(position, BattleSide.OPPONENT, FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT),
            responseMemory, responseInformation,
        )
        if (allyActions.isEmpty() || opponentActions.isEmpty()) return leafValue(position)
        var best = Double.NEGATIVE_INFINITY
        for (allyAction in allyActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            for (opponentAction in opponentActions) {
                val child = descend(position, allyAction, opponentAction) ?: return null
                val value = projectedValue(child, depthRemaining - 1, worstResponse) ?: return null
                worstResponse = minOf(worstResponse, value)
                // An earlier ally action already guarantees best. Once a reply holds this one to no more, the
                // remaining replies can only lower it further, so the maximum and the cached value stay exact.
                if (worstResponse <= best) break
            }
            best = maxOf(best, worstResponse)
            // The parent is minimizing responses and already has one worth upperBound. Once this
            // position can guarantee at least that value, its remaining ally actions cannot lower
            // the parent's minimum. This is only a lower bound, so never cache a cutoff result.
            if (best >= upperBound) return best
        }
        val result = if (best.isFinite()) best else leafValue(position) ?: return null
        if (!truncated) valueCache[key] = result
        return result
    }

    /**
     * Preserve the value of progress already realized this turn. A leaf-only horizon makes
     * "damage now, finish next turn" tie with "idle now, deal all damage next turn" whenever the
     * final board is the same; that gave near-full-HP Roost an unearned share of the root draw.
     * Intermediate material is cheap to read from the native state; the full positional evaluator
     * remains at the leaf. An ended battle has no later turn to discount.
     */
    private fun projectedValue(
        child: NativeSearchPosition,
        depthRemaining: Int,
        upperBound: Double,
    ): Double? {
        if (depthRemaining <= 0 || child.frame.ended) {
            return positionValue(child, depthRemaining, upperBound)
        }
        val immediateMaterial = LocalBoardMaterial.evaluate(child.state) + child.recoilCredit
        val remainingWeight = FUTURE_VALUE_WEIGHT
        val immediateWeight = 1.0 - remainingWeight
        // The parent's bound is expressed after this affine blend. Map it into the child's units
        // before pruning; otherwise a cutoff may contaminate another root action's exact score.
        val childBound = if (upperBound.isFinite()) {
            (upperBound - immediateWeight * immediateMaterial) / remainingWeight
        } else {
            upperBound
        }
        val continuation = positionValue(child, depthRemaining, childBound) ?: return null
        return immediateWeight * immediateMaterial + remainingWeight * continuation
    }

    private fun leafValue(position: NativeSearchPosition): Double? {
        if (!position.frame.ended && position.frame.requestState == REPLACEMENT_REQUEST) {
            return replacementValue(position)
        }
        return evaluate(position.state) + position.recoilCredit + pendingHealValue(position.frame)
    }

    /**
     * A knockout leaves the battle waiting for a replacement. Scored there, the side that lost a Pokemon has no
     * active one, so nothing this side's active gained - a stat boost from the knocking-out move, say - was weighed
     * against anybody, and Draco Meteor scored the same as Dragon Pulse. The replacement comes in before the next
     * turn whatever either side chooses, so it is played out here, each side picking its best, without counting as
     * a turn of the horizon.
     */
    private fun replacementValue(position: NativeSearchPosition): Double? {
        val allyActions = tree.actions(position, BattleSide.ALLY)
        val opponentActions = tree.actions(position, BattleSide.OPPONENT)
        if (allyActions.isEmpty() || opponentActions.isEmpty()) {
            return evaluate(position.state) + position.recoilCredit + pendingHealValue(position.frame)
        }
        var best = Double.NEGATIVE_INFINITY
        for (allyAction in allyActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            for (opponentAction in opponentActions) {
                val child = descend(position, allyAction, opponentAction) ?: return null
                worstResponse = minOf(worstResponse, leafValue(child) ?: return null)
                if (worstResponse <= best) break
            }
            best = maxOf(best, worstResponse)
        }
        return best
    }

    private fun pendingHealValue(frame: NativeBattleFrame): Double = NativeSearchLeafTerms.pendingHeal(frame)

    private fun hpAdvantage(position: NativeSearchPosition): Double = NativeSearchLeafTerms.hpAdvantage(position)

    private fun descend(
        position: NativeSearchPosition,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
    ): NativeSearchPosition? {
        if (!timeAvailable()) return null
        val key = BranchKey(
            tree.rulesFingerprint,
            world.hypothesisId,
            world.randomSampleIndex,
            position.frame.snapshotJson,
            allyAction.actionId,
            opponentAction.actionId,
        )
        branchCache[key]?.let { return it }
        if (nodesVisited >= nodeLimit) {
            stop(NativeSearchTerminationReason.NODE_BUDGET)
            return null
        }
        nodesVisited++
        return tree.branch(position, allyAction, opponentAction).also { branchCache[key] = it }
    }

    private fun timeAvailable(): Boolean {
        if (truncated) return false
        if (shouldContinue()) return true
        stop(NativeSearchTerminationReason.DEADLINE)
        return false
    }

    private fun stop(reason: NativeSearchTerminationReason) {
        if (!truncated) {
            truncated = true
            terminationReason = reason
        }
    }

    private data class BranchKey(
        val rulesFingerprint: String,
        val hypothesisId: String,
        val randomSampleIndex: Int,
        val snapshotJson: String,
        val allyActionId: String,
        val opponentActionId: String,
    )

    private data class ValueKey(
        val rulesFingerprint: String,
        val hypothesisId: String,
        val randomSampleIndex: Int,
        val snapshotJson: String,
        val depthRemaining: Int,
    )

    private companion object {
        const val FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT = 1
        /** Showdown's request state while a side must send in a replacement. */
        const val REPLACEMENT_REQUEST = "switch"
        const val DEFAULT_CACHE_ENTRY_LIMIT = 2_048
        const val FUTURE_VALUE_WEIGHT = 0.90
        const val ROOT_TEMPO_WEIGHT = 0.75
    }
}

/** Value terms both native searches add to a scored position, so their root values share one scale. */
internal object NativeSearchLeafTerms {
    /**
     * A Wish heals at the end of the turn after it was used, past a one-turn horizon. Without this a search that
     * stops there saw Wish as a wasted turn and Protect as the better way to stall, so a STANDARD Alomomola
     * protected again rather than wishing. Discounted like the legacy Wish value: the slot may change hands.
     */
    fun pendingHeal(frame: NativeBattleFrame): Double = frame.field.pendingHeals.orEmpty().sumOf { heal ->
        val ally = frame.p1Team.firstOrNull { it.uuid == heal.targetUuid }
        val target = ally ?: frame.p2Team.firstOrNull { it.uuid == heal.targetUuid } ?: return@sumOf 0.0
        if (target.hp <= 0 || target.maxHp <= 0) return@sumOf 0.0
        val gained = minOf(heal.hp, target.maxHp - target.hp).coerceAtLeast(0).toDouble() / target.maxHp
        (if (ally != null) 1.0 else -1.0) * gained * PENDING_HEAL_DISCOUNT
    }

    fun hpAdvantage(position: NativeSearchPosition): Double =
        position.frame.p1Team.sumOf { it.hp.toDouble() / it.maxHp } -
            position.frame.p2Team.sumOf { it.hp.toDouble() / it.maxHp } +
            // First-turn tempo measures progress, not the price of a move. Recoil is already
            // priced in the material value, so remove it completely from this extra tempo term.
            position.recoilCredit * 2.0

    /** The legacy Wish value's discount for waiting a turn. */
    private const val PENDING_HEAL_DISCOUNT = 0.6
}
