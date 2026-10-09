package jbro.cobblemon.mcc.betterai.search

import java.util.Random
import kotlin.math.ln
import kotlin.math.sqrt
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeRootActionMatcher
import jbro.cobblemon.mcc.betterai.simulation.NativeSearchPosition
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRuntimeService
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree

private typealias NativeInformationSetLease = (
    deadlineNanos: Long,
    action: (NativeBranchWorker) -> NativeProductWorldSearchResult,
) -> NativeProductWorldSearchResult?

/**
 * Information-set Monte Carlo tree search over every posterior world at once.
 *
 * The per-world search played each hidden world to the full horizon on its own, so its cost was the
 * number of worlds times a whole game tree: a Boss opening holds 96 worlds and never got past the first
 * turn. Here one tree is keyed by the public choices of both sides. Every iteration draws one world by
 * its posterior probability and walks the tree in that world, so the worlds share the tree and the
 * clock goes to the likely worlds and the promising choices instead of to every world equally.
 *
 * Turns are simultaneous, but the opponent's reply is chosen under each of this side's choices, as the
 * per-world minimax did: this side's value for a choice is what the opponent's best reply to it leaves.
 * Values are backed up as minimax, not as the mean of the iterations: every node's value is recomputed
 * along the walked path as this side's best choice against the opponent's worst reply among those
 * tried, each edge averaging the worlds that reached it. A mean counted every exploratory reply and
 * choice, so even a fully expanded tree did not reach the per-world search's values and a Boss
 * opening looked far too hopeful.
 *
 * A value whose subtree is not fully searched can only be too high: a reply not yet tried may be worse.
 * This side's UCB keeps revisiting such a choice, but the opponent avoids a reply that looks good for
 * this side, so an overrated reply would never be corrected. A reply or choice is settled once its value
 * is proven: a choice when every reply to it is settled, a position when its best choice is settled and
 * every other one was tried, since their values bound them from above. A reply also needs no proof once a
 * settled choice after it already leaves this side at least as well off as a settled sibling reply: the
 * opponent will not prefer it, as in an alpha-beta cutoff. The opponent turns to an
 * unsettled reply, the lowest first, before exploring; this side settles its best choice first, and at
 * the root every choice, the likeliest first, since every root value is ranked. The search ends when
 * every root choice is settled and further worlds stop adding positions, or at the clock or node budget.
 * The opponent knows its own Pokemon, so its replies keep separate statistics for every set its active
 * Pokemon has in some world; worlds that differ only on the bench share them. A choice's value is the
 * settled reply of each such set, weighted by how often that set was drawn. One set of statistics for
 * every world had the opponent pick replies that were good on average over sets it could not all have,
 * and let a move the opponent's actual set punishes look safe.
 * An opponent's move is keyed by the move rather than its slot, since the slot differs between the
 * worlds that guess its moveset. An arm a world does not offer is skipped there and counts its
 * availability, not the parent's visits, as subset-armed bandits do.
 *
 * Every iteration plays to the same horizon, and a forced replacement after a knockout is played in
 * the tree without spending a turn of it, so every backed-up value has the same depth. Values are on
 * the scale of [NativeRecursiveSearch]: the leaf evaluation plus recoil and pending heals, each played
 * turn before the last blended with that turn's material, and first-turn tempo plus the AI-only threat
 * term at the root.
 */
internal class NativeInformationSetSearch(
    /**
     * Lets a replacement spend a turn of the horizon, as in the per-world search; only for proving the two
     * searches agree. A knockout line then saw one turn less than every other line.
     */
    private val replacementSpendsTurn: Boolean = false,
    private val lease: NativeInformationSetLease = { deadlineNanos, action ->
        NativeShowdownRuntimeService.withWorker(deadlineNanos, action)
    },
    /** Diagnostics only: receives every root choice's replies, grouped by opponent set, once the search ends. */
    private val rootReport: ((String) -> Unit)? = null,
) {
    fun search(request: NativeProductWorldSearchRequest): NativeProductWorldSearchResult {
        val first = request.worlds.first()
        if (request.nanoTime() - request.deadlineNanos >= 0L) {
            return failure(NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH, first, 0,
                NativeProductSearchRun(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED))
        }
        val result = try {
            lease(request.deadlineNanos) { worker ->
                try {
                    Run(request, worker, replacementSpendsTurn, rootReport).execute()
                } catch (abort: Abort) {
                    abort.result
                }
            }
        } catch (error: Exception) {
            return failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, first, 0,
                NativeProductSearchRun(NativeProductSearchRunStatus.NATIVE_EXECUTION_FAILURE, failure = error))
        } catch (error: LinkageError) {
            return failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, first, 0,
                NativeProductSearchRun(NativeProductSearchRunStatus.NATIVE_EXECUTION_FAILURE, failure = error))
        }
        if (result != null) return result
        val status = if (request.nanoTime() - request.deadlineNanos >= 0L) {
            NativeProductSearchRunStatus.DEADLINE_EXHAUSTED
        } else {
            NativeProductSearchRunStatus.RUNTIME_UNAVAILABLE
        }
        val searchStatus = if (status == NativeProductSearchRunStatus.DEADLINE_EXHAUSTED) {
            NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH
        } else {
            NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED
        }
        return failure(searchStatus, first, 0, NativeProductSearchRun(status))
    }

    private class Run(
        private val request: NativeProductWorldSearchRequest,
        private val worker: NativeBranchWorker,
        private val replacementSpendsTurn: Boolean,
        private val rootReport: ((String) -> Unit)?,
    ) {
        /**
         * The per-world search's attack-only third turn for Boss setup lines is not taken: it was admitted only
         * when the node budget fitted, which a Boss opening never did, and a three-turn tree did not settle in
         * the clock. Setup is priced at the root by `LocalSetupMovePreference` as before.
         */
        private val horizon = request.maxDepth
        /** The root candidates searched and valued; the rules' exclusions only take part in the mapping. */
        private val rootActions = request.productActions.filter { it.actionId !in request.excludedRootActionIds }
        private val attackOnlyPly = if (horizon > 1 && request.finalPlyAttacksOnly) horizon - 1 else -1
        private val root = Node()
        private val worlds = arrayOfNulls<PreparedWorld>(request.worlds.size)
        private val random = Random(request.worlds.fold(SEED_BASE) { seed, world ->
            seed * 31 + world.key.hashCode()
        })
        private var nodesVisited = 0
        private var iterations = 0
        private val branches = object : LinkedHashMap<BranchKey, NativeSearchPosition>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BranchKey, NativeSearchPosition>?): Boolean =
                size > BRANCH_CACHE_LIMIT
        }
        private var followedCache: Set<String>? = null
        private var followedCacheIteration = -1
        private var minValue = Double.POSITIVE_INFINITY
        private var maxValue = Double.NEGATIVE_INFINITY

        fun execute(): NativeProductWorldSearchResult {
            // Every world gets its native root first, likeliest first: a world without one could neither be
            // searched nor carried into the next turn's session.
            val preparationOrder = request.worlds.indices.sortedWith(
                compareByDescending<Int> { request.worlds[it].probability }
                    .thenBy { request.worlds[it].key.hypothesisId }
                    .thenBy { request.worlds[it].key.randomSampleIndex }
                    .thenBy { request.worlds[it].key.lineage },
            )
            for (index in preparationOrder) {
                if (!timeAvailable()) break
                worlds[index] = prepare(index)
            }
            val prepared = worlds.filterNotNull()
            if (prepared.isEmpty()) {
                return failure(NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH, request.worlds.first(),
                    0, NativeProductSearchRun(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED))
            }
            val cumulative = DoubleArray(prepared.size)
            var mass = 0.0
            prepared.forEachIndexed { index, world ->
                mass += world.input.probability
                cumulative[index] = mass
            }

            var idleIterations = 0
            var settledAtNodes: Int? = null
            var settledRootValues = emptyMap<String, Double>()
            while (nodesVisited < request.nodeLimit && idleIterations < IDLE_ITERATION_LIMIT) {
                if (!timeAvailable()) break
                val draw = random.nextDouble() * mass
                val pick = cumulative.indexOfFirst { draw < it }.let { if (it < 0) prepared.lastIndex else it }
                val before = nodesVisited
                if (!iterate(prepared[pick])) break
                iterations++
                val settled = rootSettled()
                if (settled && settledAtNodes == null) {
                    settledAtNodes = nodesVisited
                    settledRootValues = rootActions.associate { it.actionId to root.ally.getValue(it.actionId).value }
                }
                idleIterations = if (settled && nodesVisited == before) idleIterations + 1 else 0
            }
            val proven = rootSettled()
            rootReport?.let { report ->
                for (action in rootActions) {
                    val arm = root.ally[action.actionId] ?: continue
                    report("ROOT ${action.actionId} value=${"%.3f".format(arm.value)} settled=${arm.settled} visits=${arm.visits}")
                    arm.replies.values.filter { it.visits > 0 }.groupBy { it.opponentSet }.forEach { (set, replies) ->
                        report("  SET ${set.substringAfter("/")} visits=${replies.sumOf { it.visits }} stats=" +
                            replies.first().child.entries.values.firstOrNull()?.position?.frame?.p2Team
                                ?.firstOrNull { it.activeSlot != null }?.let { "${it.stats} lv${it.level}" })
                        replies.sortedBy { it.value }.forEach { reply ->
                            report("    ${reply.key.substringAfter(SET_SEPARATOR).takeLast(40)} v=${"%.3f".format(reply.value)} " +
                                "n=${reply.visits} threat=${"%.3f".format(reply.threatSum / reply.visits)} " +
                                "tempo=${"%.3f".format(reply.tempoSum / reply.visits)} settled=${reply.settled} " +
                                reply.child.entries.values.firstOrNull()?.position?.frame?.let { frame ->
                                    (frame.p1Team + frame.p2Team).filter { it.activeSlot != null || it.hp == 0 }
                                        .joinToString(" ") { "${it.species}:${it.hp}/${it.maxHp}" }
                                }.orEmpty())
                        }
                    }
                }
            }

            val rootValues = rootActions.map { action ->
                val arm = root.ally[action.actionId]
                if (arm == null || arm.visits == 0) {
                    return failure(NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH, prepared.first().input,
                        nodesVisited, NativeProductSearchRun(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED))
                }
                NativeRootActionValue(action, arm.value)
            }
            // Unproven root values rest on part of the horizon; they are reported as one turn deep.
            return NativeProductWorldSearchResult(
                status = if (proven) {
                    NativeProductWorldSearchStatus.COMPLETED
                } else {
                    NativeProductWorldSearchStatus.PARTIAL_DEPTH
                },
                rootValues = rootValues,
                depthCompleted = if (proven) request.maxDepth else 1,
                nodesVisited = nodesVisited,
                rootSnapshots = prepared.associate { it.input.key to it.rootSnapshot },
                iterations = iterations,
                settledAtNodes = settledAtNodes,
                settledRootValues = settledRootValues,
            )
        }

        private fun prepare(index: Int): PreparedWorld {
            val input = request.worlds[index]
            val supplied = input.rootSnapshot
            if (supplied != null && supplied.rulesFingerprint != worker.rulesFingerprint) {
                throw Abort(failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, input, nodesVisited,
                    NativeProductSearchRun(NativeProductSearchRunStatus.RULES_GENERATION_MISMATCH)))
            }
            val rootFrame = supplied?.frame ?: worker.createBattle(input.definition)
            val publicTurnOffset = supplied?.publicTurnOffset
                ?: if (input.publicState.turn == 0 && rootFrame.turn == 1) 1 else 0
            val rootIssues = NativeBattleRootValidator.validate(input.definition, rootFrame, input.publicState, publicTurnOffset)
            if (rootIssues.isNotEmpty()) {
                throw Abort(failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, input, nodesVisited,
                    NativeProductSearchRun(NativeProductSearchRunStatus.ROOT_STATE_INCONSISTENT, rootIssues = rootIssues)))
            }
            val tree = NativeShowdownSearchTree(worker, rootFrame, input.publicState, publicTurnOffset,
                input.publicActionCatalog, request.allowedMechanics)
            val mapping = NativeRootActionMatcher.match(
                tree.root.state.format, request.productActions, tree.actions(tree.root, BattleSide.ALLY))
            // A rebuilt mid-battle root does not carry every move restriction (Disable, Torment), so it may offer
            // more than the battle does; the product candidates are the legal ones, and every one must still map.
            val accepted = if (input.definition.situation != null) {
                mapping.unmatchedProductActionIds.isEmpty() && mapping.ambiguousProductActionIds.isEmpty()
            } else {
                mapping.complete
            }
            if (!accepted) {
                throw Abort(failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, input, nodesVisited,
                    NativeProductSearchRun(NativeProductSearchRunStatus.ROOT_ACTION_MAPPING_INCOMPLETE, mapping = mapping)))
            }
            val world = PreparedWorld(
                index = index,
                input = input,
                tree = tree,
                rootSnapshot = supplied ?: NativeProductRootSnapshot(worker.rulesFingerprint, rootFrame, publicTurnOffset),
                rootThreat = LocalOpponentThreat.materialAdjustment(tree.root.state, request.opponentThreatWeights),
                rootHpAdvantage = NativeSearchLeafTerms.hpAdvantage(tree.root),
            )
            val rootOpponent = tree.actions(tree.root, BattleSide.OPPONENT)
            if (rootOpponent.isEmpty()) {
                throw Abort(failure(NativeProductWorldSearchStatus.INCONSISTENT_ACTION_SET, input, nodesVisited, null))
            }
            root.entries[index] = Entry(
                position = tree.root,
                ply = 0,
                replacement = false,
                ally = ordered(tree.root, BattleSide.ALLY, rootActions).map { product ->
                    Choice(product.actionId, mapping.productToNative.getValue(product.actionId))
                },
                opponent = ordered(tree.root, BattleSide.OPPONENT, NativeOpponentResponseOrdering.order(
                    rootOpponent, request.responseMemory, request.responseInformation,
                )).let { choices(tree.root, it) },
                opponentSet = opponentSet(tree.root),
            )
            return world
        }

        /** Plays one iteration in [world]; false when the clock or node budget ran out before it finished. */
        private fun iterate(world: PreparedWorld): Boolean {
            val steps = ArrayList<Step>(horizon + 2)
            var node = root
            var entry = root.entries.getValue(world.index)
            while (!entry.terminal) {
                val ally = selectAlly(node, entry.ally)
                val allyArm = node.ally.getValue(ally.key)
                val opponent = selectReply(allyArm, entry.opponent, entry.opponentSet, node === root)
                val reply = allyArm.replies.getValue(opponent.key)
                val child = reply.child
                val childEntry = child.entries[world.index] ?: run {
                    if (nodesVisited >= request.nodeLimit || !timeAvailable()) return false
                    val position = branch(world, entry.position, ally.action, opponent.action)
                    entry(world, position, if (entry.replacement && !replacementSpendsTurn) entry.ply else entry.ply + 1)
                        .also { child.entries[world.index] = it }
                }
                steps += Step(node, allyArm, reply, childEntry)
                node = child
                entry = childEntry
            }
            val leaf = entry.leafValue ?: (world.input.evaluate(entry.position.state) +
                entry.position.recoilCredit + NativeSearchLeafTerms.pendingHeal(entry.position.frame))
            if (!timeAvailable() || !leaf.isFinite()) return false
            entry.leafValue = leaf
            if (steps.isEmpty()) return true

            for (index in steps.indices.reversed()) {
                val step = steps[index]
                val reply = step.reply
                val child = step.child
                reply.visits++
                when {
                    child.terminal -> reply.terminalSum += child.leafValue ?: leaf
                    // A pending replacement is played out without spending a turn: its value passes on unchanged.
                    child.replacement && (!replacementSpendsTurn || child.ply >= horizon) -> reply.passedVisits++
                    // A played turn before the last keeps the progress it made, as in the per-world search.
                    else -> {
                        reply.blendedVisits++
                        reply.blendedMaterialSum +=
                            LocalBoardMaterial.evaluate(child.position.state) + child.position.recoilCredit
                    }
                }
                if (index == 0) {
                    if (request.opponentResponseLimit != null) reply.firstTurnSum += firstTurnValue(world, child)
                    if (horizon > 1) {
                        reply.tempoSum += (NativeSearchLeafTerms.hpAdvantage(child.position) - world.rootHpAdvantage) *
                            ROOT_TEMPO_WEIGHT
                    }
                    // The opponent's choice follows the plain value; only the AI's own estimate carries the
                    // threat it puts on the opponent's Pokemon.
                    reply.threatSum += LocalOpponentThreat.materialAdjustment(
                        child.position.state, request.opponentThreatWeights) - world.rootThreat
                }
                reply.value = (reply.terminalSum + (1.0 - FUTURE_VALUE_WEIGHT) * reply.blendedMaterialSum +
                    (FUTURE_VALUE_WEIGHT * reply.blendedVisits + reply.passedVisits) * reply.child.value +
                    reply.tempoSum) / reply.visits
                val continued = reply.blendedVisits + reply.passedVisits
                reply.settled = continued == 0 || reply.child.settled
                reply.lowerBound = when {
                    continued == 0 -> reply.value
                    reply.child.lowerBound == Double.NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
                    else -> (reply.terminalSum + (1.0 - FUTURE_VALUE_WEIGHT) * reply.blendedMaterialSum +
                        (FUTURE_VALUE_WEIGHT * reply.blendedVisits + reply.passedVisits) * reply.child.lowerBound +
                        reply.tempoSum) / reply.visits
                }
                widen(reply.value)
                val ally = step.ally
                ally.visits++
                ally.value = settledValue(ally)
                val proving = provingReplies(ally, step.node === root)
                ally.settled = proving.all { it.visits > 0 } && unresolved(proving).isEmpty()
                val node = step.node
                val best = node.ally.values.filter { it.visits > 0 }.maxBy(AllyArm::value)
                node.value = best.value
                node.settled = best.settled && node.offered.all { (node.ally[it]?.visits ?: 0) > 0 }
                node.lowerBound = node.ally.values.filter { it.settled }.maxOfOrNull(AllyArm::value)
                    ?: Double.NEGATIVE_INFINITY
            }
            return true
        }

        /**
         * This side's value for one choice: for every opponent set that met it, the worst reply that set has
         * tried, weighted by how often the set was drawn there. A set is drawn with its worlds' probability
         * whatever this side chooses, so its share of the visits is its posterior share.
         */
        private fun settledValue(arm: AllyArm): Double {
            val followed = if (arm.atRoot) followedReplies() else null
            val replies = arm.replies.values.filter { it.visits > 0 }
                .let { tried -> followed?.let { keys -> tried.filter { it.key in keys }.ifEmpty { tried } } ?: tried }
            val total = replies.sumOf { it.visits }.toDouble()
            return replies.groupBy { it.opponentSet }.values.sumOf { group ->
                val worst = group.minBy(ReplyArm::value)
                group.sumOf { it.visits } / total * (worst.value + worst.threatSum / worst.visits)
            }
        }

        private fun entry(world: PreparedWorld, position: NativeSearchPosition, ply: Int): Entry {
            val replacement = !position.frame.ended && position.frame.requestState == REPLACEMENT_REQUEST
            if (position.frame.ended || ply >= horizon && !replacement) {
                return Entry(position, ply, replacement, emptyList(), emptyList())
            }
            val tree = world.tree
            val allyActions = when {
                replacement -> tree.actions(position, BattleSide.ALLY)
                ply == attackOnlyPly -> tree.attackingActions(position)
                else -> tree.actions(position, BattleSide.ALLY,
                    if (request.excludeFutureAllyVoluntarySwitches) 0 else FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT)
            }.let { ordered(position, BattleSide.ALLY, it) }
            val opponentActions = if (replacement) {
                tree.actions(position, BattleSide.OPPONENT)
            } else {
                NativeOpponentResponseOrdering.order(
                    tree.actions(position, BattleSide.OPPONENT, FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT),
                    request.responseMemory, request.responseInformation,
                )
            }.let { ordered(position, BattleSide.OPPONENT, it) }
            if (allyActions.isEmpty() || opponentActions.isEmpty()) {
                return Entry(position, ply, replacement, emptyList(), emptyList())
            }
            return Entry(
                position = position,
                ply = ply,
                replacement = replacement,
                ally = allyActions.map { Choice(it.actionId, it) },
                opponent = choices(position, opponentActions),
                opponentSet = opponentSet(position),
            )
        }

        /**
         * Untried choices are opened in this order, and away from the root one at a time as alpha-beta does, so the
         * likeliest best first lets a proven sibling cut the rest off sooner. The values do not depend on it.
         */
        private fun ordered(position: NativeSearchPosition, side: BattleSide, actions: List<BattleActionCandidate>) =
            NativeMatchupPrior.order(request.actionPrior, position.state, side, actions)

        private fun choices(position: NativeSearchPosition, actions: List<BattleActionCandidate>): List<Choice> {
            val set = opponentSet(position)
            return actions.map { Choice(set + SET_SEPARATOR + opponentKey(it), it) }
        }

        /**
         * UCB1 over the arms this world offers. An untried arm goes first in the offered order, which for the
         * opponent is the observed-tendency order. [limit] keeps a narrower tier's opponent to its few
         * replies that hurt this side most once every reply has been tried.
         */
        /**
         * Whether every root value is proven. A narrower tier's followed replies move as their first-turn values
         * come in, so the root choices' flags are refreshed against the current ones first.
         */
        private fun rootSettled(): Boolean = rootActions.all { action ->
            val arm = root.ally[action.actionId] ?: return@all false
            if (request.opponentResponseLimit != null) {
                val proving = provingReplies(arm, atRoot = true)
                arm.settled = proving.all { it.visits > 0 } && unresolved(proving).isEmpty()
            }
            arm.settled
        }

        /**
         * The tried replies still to prove: neither settled nor cut off by a settled sibling of the same opponent
         * set that already leaves this side worse.
         */
        private fun unresolved(replies: List<ReplyArm>): List<ReplyArm> {
            val bestForOpponent = HashMap<String, Double>()
            for (reply in replies) {
                if (reply.visits > 0 && reply.settled) {
                    bestForOpponent.merge(reply.opponentSet, reply.value, ::minOf)
                }
            }
            return replies.filter { reply ->
                reply.visits > 0 && !reply.settled &&
                    bestForOpponent[reply.opponentSet]?.let { reply.lowerBound >= it } != true
            }
        }

        /**
         * A root reply's value after one turn, which a narrower tier picks its replies by. A knockout leaves the
         * battle waiting for a replacement, so that is played out first, each side picking its best, as the
         * per-world search's leaf did.
         */
        private fun firstTurnValue(world: PreparedWorld, entry: Entry): Double {
            entry.leafValue?.let { return it }
            entry.firstTurnValue?.let { return it }
            return settledFirstTurn(world, entry.position).also { entry.firstTurnValue = it }
        }

        /**
         * A native transition is deterministic within a world, and different choices often reach the same battle
         * (a move that fails, a reply into Protect), so transitions are shared by snapshot as in the per-world
         * search; only a new one counts as a node.
         */
        private fun branch(
            world: PreparedWorld,
            position: NativeSearchPosition,
            allyAction: BattleActionCandidate,
            opponentAction: BattleActionCandidate,
        ): NativeSearchPosition {
            val key = BranchKey(world.index, position.frame.snapshotJson, allyAction.actionId, opponentAction.actionId)
            branches[key]?.let { return it }
            nodesVisited++
            return world.tree.branch(position, allyAction, opponentAction).also { branches[key] = it }
        }

        private fun settledFirstTurn(world: PreparedWorld, position: NativeSearchPosition): Double {
            val static = world.input.evaluate(position.state) + position.recoilCredit +
                NativeSearchLeafTerms.pendingHeal(position.frame)
            if (position.frame.ended || position.frame.requestState != REPLACEMENT_REQUEST) return static
            val allyActions = world.tree.actions(position, BattleSide.ALLY)
            val opponentActions = world.tree.actions(position, BattleSide.OPPONENT)
            if (allyActions.isEmpty() || opponentActions.isEmpty()) return static
            var best = Double.NEGATIVE_INFINITY
            for (allyAction in allyActions) {
                var worst = Double.POSITIVE_INFINITY
                for (opponentAction in opponentActions) {
                    worst = minOf(worst, settledFirstTurn(world, branch(world, position, allyAction, opponentAction)))
                    if (worst <= best) break
                }
                best = maxOf(best, worst)
            }
            return best
        }

        /** The replies whose values a choice's value rests on: every one, or a narrower tier's few at the root. */
        private fun provingReplies(arm: AllyArm, atRoot: Boolean): List<ReplyArm> {
            val offered = arm.offered.map { key -> arm.replies[key] ?: return listOf(UNTRIED) }
            val followed = if (atRoot) followedReplies() else null
            return if (followed == null) offered else offered.filter { it.key in followed }
        }

        /**
         * A narrower tier follows past the first turn only the few replies of each opponent set that left this
         * side worst after one turn, averaged over all its root choices, as the per-world search did. One
         * common set: replies chosen per choice let a move whose own worst replies happened to be mild outrank
         * one that met the dangerous ones. Null while there is no limit or a root reply is untried.
         */
        private fun followedReplies(): Set<String>? {
            val limit = request.opponentResponseLimit ?: return null
            followedCache?.takeIf { followedCacheIteration == iterations }?.let { return it }
            val firstTurn = HashMap<String, Pair<String, MutableList<Double>>>()
            for (arm in root.ally.values) {
                for (reply in arm.replies.values) {
                    if (reply.visits == 0) return null
                    firstTurn.getOrPut(reply.key) { reply.opponentSet to mutableListOf() }.second +=
                        reply.firstTurnSum / reply.visits
                }
            }
            return firstTurn.entries.groupBy { it.value.first }.values.flatMap { set ->
                set.sortedBy { it.value.second.average() }.take(limit).map { it.key }
            }.toSet().also {
                followedCache = it
                followedCacheIteration = iterations
            }
        }

        private fun selectAlly(node: Node, offered: List<Choice>): Choice {
            node.offered += offered.map(Choice::key)
            for (choice in offered) node.ally.getOrPut(choice.key) { AllyArm(node === root) }.available++
            if (node !== root) {
                // Away from the root one choice is proven at a time, as alpha-beta does: the one in progress is
                // finished before the next is opened, and a choice held to no more than a proven one is dropped.
                // Opening every choice first spent a node on each even where the parent could cut this one off.
                offered.firstOrNull {
                    val arm = node.ally.getValue(it.key)
                    arm.visits > 0 && !arm.settled && arm.value > node.lowerBound
                }?.let { return it }
                offered.firstOrNull { node.ally.getValue(it.key).visits == 0 }?.let { return it }
                return ucb(node.ally, offered, maximize = true)
            }
            offered.firstOrNull { node.ally.getValue(it.key).visits == 0 }?.let { return it }
            // Every root value is ranked, so every root choice is proven, the likeliest best first.
            offered.filter { !node.ally.getValue(it.key).settled }
                .maxByOrNull { node.ally.getValue(it.key).value }?.let { return it }
            return ucb(node.ally, offered, maximize = true)
        }

        private fun selectReply(arm: AllyArm, offered: List<Choice>, opponentSet: String, atRoot: Boolean): Choice {
            arm.offered += offered.map(Choice::key)
            for (choice in offered) arm.replies.getOrPut(choice.key) { ReplyArm(choice.key, opponentSet) }.available++
            offered.firstOrNull { arm.replies.getValue(it.key).visits == 0 }?.let { return it }
            val followed = if (atRoot) followedReplies() else null
            val candidates = followed?.let { keys -> offered.filter { it.key in keys }.ifEmpty { offered } } ?: offered
            val open = unresolved(candidates.map { arm.replies.getValue(it.key) }).mapTo(HashSet()) { it.key }
            candidates.filter { it.key in open }
                .minByOrNull { arm.replies.getValue(it.key).value }?.let { return it }
            return ucb(arm.replies, candidates, maximize = false)
        }

        private fun <T : Arm> ucb(arms: HashMap<String, T>, candidates: List<Choice>, maximize: Boolean): Choice {
            val range = maxValue - minValue
            var best = candidates.first()
            var bestScore = Double.NEGATIVE_INFINITY
            for (choice in candidates) {
                val arm = arms.getValue(choice.key)
                val normalized = if (range > 0.0) ((arm.value - minValue) / range).coerceIn(0.0, 1.0) else 0.5
                val exploitation = if (maximize) normalized else 1.0 - normalized
                val score = exploitation + EXPLORATION * sqrt(ln(arm.available.toDouble()) / arm.visits)
                if (score > bestScore) {
                    bestScore = score
                    best = choice
                }
            }
            return best
        }

        private fun widen(value: Double) {
            if (value < minValue) minValue = value
            if (value > maxValue) maxValue = value
        }

        private fun timeAvailable(): Boolean = request.nanoTime() - request.deadlineNanos < 0L
    }

    private class PreparedWorld(
        val index: Int,
        val input: NativeProductWorldSearchInput,
        val tree: NativeShowdownSearchTree,
        val rootSnapshot: NativeProductRootSnapshot,
        val rootThreat: Double,
        val rootHpAdvantage: Double,
    )

    private class Node {
        val ally = HashMap<String, AllyArm>()
        /** Every choice some world offered here. */
        val offered = HashSet<String>()
        var settled = false
        /** The best proven choice's value: this side gets at least this here. */
        var lowerBound = Double.NEGATIVE_INFINITY
        /** This side's best settled choice here. */
        var value = 0.0
        /** The node's position in each world that has reached it, by world index. */
        val entries = HashMap<Int, Entry>()
    }

    private open class Arm {
        var visits = 0
        var available = 0
        /** The minimax estimate through this arm, never below the proven value. */
        var value = 0.0
        /** The value is proven for the worlds that reached it; see the class comment. */
        var settled = false
    }

    /** One of this side's choices and the opponent's replies to it. */
    private class AllyArm(val atRoot: Boolean) : Arm() {
        val replies = HashMap<String, ReplyArm>()
        /** Every reply some world offered to this choice. */
        val offered = HashSet<String>()
    }

    private class ReplyArm(
        val key: String,
        /** The opponent's active set this reply was made with; see [opponentSet]. */
        val opponentSet: String,
    ) : Arm() {
        /** The position's value after the first turn, at the root only. */
        var firstTurnSum = 0.0
        /** What this side gets at least through this reply, from the proven choices after it. */
        var lowerBound = Double.NEGATIVE_INFINITY
        /** Leaf values of the visits whose world ended or reached the horizon here. */
        var terminalSum = 0.0
        /** Visits that played on after this turn, and the material that turn left. */
        var blendedVisits = 0
        var blendedMaterialSum = 0.0
        /** Visits that played on into a replacement. */
        var passedVisits = 0
        /** First-turn tempo, at the root only. */
        var tempoSum = 0.0
        /** The AI-only threat term at the root, kept apart because the opponent does not choose by it. */
        var threatSum = 0.0
        val child = Node()
    }

    private class Choice(val key: String, val action: BattleActionCandidate)

    private data class BranchKey(val world: Int, val snapshotJson: String, val allyActionId: String, val opponentActionId: String)

    private class Entry(
        val position: NativeSearchPosition,
        val ply: Int,
        val replacement: Boolean,
        val ally: List<Choice>,
        val opponent: List<Choice>,
        val opponentSet: String = "",
    ) {
        val terminal: Boolean get() = ally.isEmpty()
        /** A transition is deterministic within a world, so its leaf evaluation is too. */
        var leafValue: Double? = null
        /** The static value of a root child that plays on, for a narrower tier's reply choice. */
        var firstTurnValue: Double? = null
    }

    private class Step(
        val node: Node,
        val ally: AllyArm,
        val reply: ReplyArm,
        val child: Entry,
    )

    private class Abort(val result: NativeProductWorldSearchResult) : RuntimeException(null, null, false, false)

    private companion object {
        const val REPLACEMENT_REQUEST = "switch"
        const val FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT = 1
        const val FUTURE_VALUE_WEIGHT = 0.90
        const val ROOT_TEMPO_WEIGHT = 0.75
        const val EXPLORATION = 0.7
        /** Stop once this many iterations in a row met only positions every world had already reached. */
        const val IDLE_ITERATION_LIMIT = 300
        const val SEED_BASE = 0x5EED_1A55L
        private val MOVE_SLOT = Regex(":move:\\d+:")

        const val SET_SEPARATOR = "|"
        const val BRANCH_CACHE_LIMIT = 16_384
        /** Stands for a reply some world offered that none has tried yet. */
        private val UNTRIED = ReplyArm("", "")

        /** What the opponent knows of its active Pokemon and the worlds may disagree on. */
        fun opponentSet(position: NativeSearchPosition): String = position.frame.p2Team
            .filter { it.activeSlot != null && it.hp > 0 }
            .sortedBy { it.activeSlot }
            .joinToString(";") { pokemon ->
                val set = pokemon.sourceSet
                if (set == null) {
                    "${pokemon.uuid}/${pokemon.species}/${pokemon.ability}/${pokemon.item}/" +
                        pokemon.moves.joinToString(",") { it.id }
                } else {
                    "${pokemon.uuid}/${set.species}/${set.ability}/${set.item}/${set.moves.sorted().joinToString(",")}/" +
                        "${set.nature}/${set.evs.toSortedMap()}/${set.ivs.toSortedMap()}/${set.teraType}"
                }
            }

        /** The same opponent move in a different moveset slot is the same public choice. */
        fun opponentKey(action: BattleActionCandidate): String = action.actionId.replace(MOVE_SLOT, ":move:")
    }
}

private fun failure(
    status: NativeProductWorldSearchStatus,
    world: NativeProductWorldSearchInput,
    nodesVisited: Int,
    run: NativeProductSearchRun?,
) = NativeProductWorldSearchResult(
    status = status,
    nodesVisited = nodesVisited,
    failedWorldId = world.key.hypothesisId,
    failedRunStatus = run?.status,
    failedRunDetail = run?.failureDetail(),
)
