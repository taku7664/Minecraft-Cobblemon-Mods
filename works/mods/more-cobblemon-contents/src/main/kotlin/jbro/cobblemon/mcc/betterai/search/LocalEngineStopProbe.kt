package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.matchup.MatchupScores
import jbro.cobblemon.mcc.betterai.matchup.StopTool
import jbro.cobblemon.mcc.betterai.matchup.StopToolKind
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorld
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRuntimeService
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * What an opponent can do to this side's sweeper, played out in the native engine instead of read off move names.
 *
 * The sweeper's first action is given ([Opening]: the setup move it is about to use, an attack when it is already
 * boosted, with the opponent first switching in when it is not on the field yet). For each move the opponent has in
 * each of its three likeliest worlds, the engine plays three turns against the sweeper's opening and best attack:
 * the move then attacks, the move every turn, or an attack and the move the turn after (two seeds each). The move
 * stops the sweeper when, in any of those, the sweeper faints or leaves the field before the opponent faints. What it did is read from the first turn's board against the opponent's own best
 * attack there, never from the move's name: the sweeper out without fainting is a forced switch, both fainting on one
 * turn Destiny Bond's kind, every move but one unusable a lock (Encore's), its setup move unusable a block (Taunt's),
 * its stages gone a reset, lower a drop, a new burn or paralysis that status, and the room's speed order flipped
 * Trick Room's. Anything else that stops it (Perish Song's count, a drain, a priority hit) is
 * [StopToolKind.PLAYED_OUT].
 */
internal object LocalEngineStopProbe {
    sealed interface Opening {
        /** The sweeper uses this move (its setup) while the opponent on the field answers. */
        data class Move(val candidate: BattleActionCandidate) : Opening
        /** The sweeper attacks; the opponent's [incomingId] first switches in, then answers next turn. */
        data class SwitchIn(val incomingId: UUID) : Opening
        /** The sweeper, already boosted, attacks while the opponent on the field answers. */
        data object Attack : Opening
    }

    interface Lease {
        fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T?
    }

    /** Production borrows the server's engine; tests set their own worker. */
    @Volatile
    var lease: Lease = object : Lease {
        override fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? =
            NativeShowdownRuntimeService.withWorker(deadlineNanos, action)
    }

    private const val WORLDS = 3
    private const val SEEDS = 2
    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> WORLDS })
    private val logger = org.slf4j.LoggerFactory.getLogger("BetterAI")

    /** One decision's board (the gate and the switch prediction ask more than once per decision). */
    private data class Key(val request: UUID, val board: Int, val sweeper: UUID, val opponent: UUID, val opening: String)
    private val cache = object : LinkedHashMap<Key, List<StopTool>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<StopTool>>) = size > 64
    }

    fun tools(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        sweeper: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
        opening: Opening,
        scores: MatchupScores,
    ): List<StopTool> {
        val openingId = when (opening) {
            is Opening.Move -> opening.candidate.actionId
            is Opening.SwitchIn -> "switch:${opening.incomingId}"
            Opening.Attack -> "attack"
        }
        val key = Key(context.requestId, System.identityHashCode(context.state), sweeper.battlePokemonId, opponent.battlePokemonId, openingId)
        synchronized(cache) { cache[key] }?.let { return it }
        val started = System.nanoTime()
        val tools = try {
            play(context, tier, sweeper, opponent, opening, scores)
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] The engine could not play {} against {}; its stop tools are left out",
                opponent.speciesId, sweeper.speciesId, e)
            null
        }
        if (tools == null) return emptyList()
        logger.debug("[BetterAI] Stop probe {} vs {}: {} in {} ms", opponent.speciesId, sweeper.speciesId, tools,
            (System.nanoTime() - started) / 1_000_000L)
        synchronized(cache) { cache[key] = tools }
        return tools
    }

    private fun play(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        sweeper: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
        opening: Opening,
        scores: MatchupScores,
    ): List<StopTool>? {
        if (context.opponentTeamPreview == null || context.exactOwnTeam == null) {
            logger.error("[BetterAI] Stop probe has no opponent preview or own team; its stop tools are left out")
            return null
        }
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            context.state.turn <= 1 -> planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        if (plan.issues.isNotEmpty() || plan.worlds.isEmpty()) {
            logger.error("[BetterAI] Stop probe could not plan the opponent's worlds: {}", plan.issues.map { it.code.name + ":" + it.detailCode })
            return null
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        val sweeperId = sweeper.battlePokemonId.toString()
        val opponentId = opponent.battlePokemonId.toString()
        // The best attack each side has against the other, from the decision's matchup table.
        val sweeperAttack = scores.pokemon(sweeper.battlePokemonId, opponent.battlePokemonId)?.subjectMove?.moveId
        val opponentAttack = scores.pokemon(opponent.battlePokemonId, sweeper.battlePokemonId)?.subjectMove?.moveId
        return lease.withWorker(deadline) { worker ->
            // Per move: the world-weighted chance it stops the sweeper, and what it did there.
            val stopChance = linkedMapOf<String, Double>()
            val kinds = linkedMapOf<String, StopToolKind>()
            for (world in plan.worlds) {
                val root = live?.let { worker.createLiveBattle(it, world.definition) } ?: worker.createBattle(world.definition)
                val start = openingTurn(worker, root, opening, sweeperAttack, world) ?: continue
                val opponentMoves = actions(BattleSide.OPPONENT, start).filter { it.kind == BattleActionKind.USE_MOVE && it.mechanic == null }
                val sweeperFirst = when (opening) {
                    is Opening.Move -> actions(BattleSide.ALLY, start).firstOrNull { it.actionId == opening.candidate.actionId }
                        ?: actions(BattleSide.ALLY, start).firstOrNull { same(it.moveId, opening.candidate.moveId) && it.mechanic == null }
                    is Opening.SwitchIn, Opening.Attack -> attack(BattleSide.ALLY, start, sweeperAttack)
                } ?: continue
                val baselineMove = opponentMoves.firstOrNull { same(it.moveId, opponentAttack) } ?: opponentMoves.firstOrNull()
                val baseline = baselineMove?.let { branch(worker, start, sweeperFirst, it, 0) }
                for (move in opponentMoves) {
                    var stops = 0.0
                    var kind: StopToolKind? = null
                    for (seed in 0 until SEEDS) {
                        val first = branch(worker, start, sweeperFirst, move, seed)
                        if (seed == 0) kind = baseline?.let { classify(first, it, sweeperId, opponentId, sweeperFirst) }
                        // The opponent's plans for its next turns: the move then attacks, the move again (Destiny Bond
                        // on the turn it is knocked out), or an attack first and the move the turn after (once the
                        // sweeper has set up). It takes the one that stops the sweeper.
                        val plans = listOf(
                            first to listOf(opponentAttack, opponentAttack),
                            first to listOf(move.moveId, move.moveId),
                        ) + (baselineMove?.let { listOf(branch(worker, start, sweeperFirst, it, seed) to listOf(move.moveId, opponentAttack)) }
                            ?: emptyList())
                        val outcome = plans.map { (after, follow) -> rollout(worker, after, sweeperId, opponentId, sweeperAttack, follow, seed) }
                            .minByOrNull { it.ordinal } ?: Outcome.HELD
                        if (outcome == Outcome.MUTUAL && kind == null) kind = StopToolKind.DESTINY_BOND
                        if (outcome == Outcome.STOPPED || outcome == Outcome.MUTUAL) stops += 1.0
                    }
                    val id = PublicIds.canonical(move.moveId.orEmpty())
                    stopChance.merge(id, world.probability * stops / SEEDS, Double::plus)
                    kind?.let { kinds.putIfAbsent(id, it) }
                }
            }
            // Every move the opponent has, a zero included: what it did is worth knowing even when the sweeper wins anyway.
            stopChance.map { (id, value) ->
                StopTool(kinds[id] ?: StopToolKind.PLAYED_OUT, id, value.coerceIn(0.0, 1.0), playedOut = true)
            }
        }
    }

    private enum class Outcome { STOPPED, MUTUAL, HELD }

    /** The turns after [after]: the sweeper's best attack against [follow], one opponent move per turn (an attack where it cannot). */
    private fun rollout(
        worker: NativeBranchWorker,
        after: NativeBattleFrame,
        sweeperId: String,
        opponentId: String,
        sweeperAttack: String?,
        follow: List<String?>,
        seed: Int,
    ): Outcome {
        var frame = after
        for (turn in 0..follow.size) {
            val sweeperGone = gone(frame.p1Team, frame.p1Active, sweeperId)
            val opponentGone = gone(frame.p2Team, frame.p2Active, opponentId)
            when {
                sweeperGone && opponentGone -> return Outcome.MUTUAL
                sweeperGone -> return Outcome.STOPPED
                opponentGone -> return Outcome.HELD
            }
            if (turn == follow.size || frame.ended || frame.requestState != "move") return Outcome.HELD
            val ally = attack(BattleSide.ALLY, frame, sweeperAttack) ?: return Outcome.HELD
            val foe = attack(BattleSide.OPPONENT, frame, follow[turn]) ?: return Outcome.HELD
            frame = branch(worker, frame, ally, foe, seed + 10 * (turn + 1))
        }
        return Outcome.HELD
    }

    /** The first turn's board against the opponent's best attack there: what this move did differently. */
    private fun classify(
        after: NativeBattleFrame,
        baseline: NativeBattleFrame,
        sweeperId: String,
        opponentId: String,
        sweeperFirst: BattleActionCandidate,
    ): StopToolKind? {
        val now = after.p1Team.firstOrNull { it.uuid == sweeperId } ?: return null
        val before = baseline.p1Team.firstOrNull { it.uuid == sweeperId } ?: return null
        val nowActive = after.p1Active.any { it.uuid == sweeperId }
        if (!nowActive && now.hp > 0 && baseline.p1Active.any { it.uuid == sweeperId }) return StopToolKind.FORCES_SWITCH
        if (now.hp <= 0 && after.p2Team.firstOrNull { it.uuid == opponentId }?.hp?.let { it <= 0 } == true) return StopToolKind.DESTINY_BOND
        val usableNow = now.moves.filterNot { it.disabled || it.pp <= 0 }.mapTo(hashSetOf()) { PublicIds.canonical(it.id) }
        val usableBefore = before.moves.filterNot { it.disabled || it.pp <= 0 }.mapTo(hashSetOf()) { PublicIds.canonical(it.id) }
        if (usableNow.size == 1 && usableBefore.size > 1) return StopToolKind.ENCORE
        val setup = PublicIds.canonical(sweeperFirst.moveId.orEmpty())
        if (setup in usableBefore && setup !in usableNow) return StopToolKind.TAUNT
        val raised = before.boosts.filterValues { it > 0 }
        if (raised.isNotEmpty() && raised.keys.all { (now.boosts[it] ?: 0) <= 0 }) return StopToolKind.RESETS_BOOSTS
        if (raised.keys.any { (now.boosts[it] ?: 0) < raised.getValue(it) }) return StopToolKind.STAT_DROP
        if (now.status != before.status && now.status.isNotBlank()) when (PublicIds.canonical(now.status)) {
            "brn" -> return StopToolKind.BURN
            "par" -> return StopToolKind.PARALYSIS
        }
        val roomNow = after.field.pseudoWeather.mapTo(hashSetOf()) { PublicIds.canonical(it.id) }
        val roomBefore = baseline.field.pseudoWeather.mapTo(hashSetOf()) { PublicIds.canonical(it.id) }
        if (roomNow != roomBefore) return StopToolKind.TRICK_ROOM
        return null
    }

    private fun openingTurn(
        worker: NativeBranchWorker,
        root: NativeBattleFrame,
        opening: Opening,
        sweeperAttack: String?,
        world: NativeInitialProductWorld,
    ): NativeBattleFrame? = when (opening) {
        is Opening.Move, Opening.Attack -> root
        is Opening.SwitchIn -> {
            val incoming = actions(BattleSide.OPPONENT, root).firstOrNull {
                it.kind == BattleActionKind.SWITCH && it.switchPokemonId == opening.incomingId
            }
            val ally = attack(BattleSide.ALLY, root, sweeperAttack)
            if (incoming == null || ally == null) null
            else branch(worker, root, ally, incoming, 0).takeIf { it.requestState == "move" && !it.ended }
        }
    }

    private fun actions(side: BattleSide, frame: NativeBattleFrame): List<BattleActionCandidate> =
        NativeShowdownRequestActionFactory.actions(side, frame, allowedMechanics = emptySet())

    /** [preferred] if it can be used, else the first move the side has, else whatever it can do. */
    private fun attack(side: BattleSide, frame: NativeBattleFrame, preferred: String?): BattleActionCandidate? {
        val all = actions(side, frame)
        val moves = all.filter { it.kind == BattleActionKind.USE_MOVE && it.mechanic == null }
        return moves.firstOrNull { same(it.moveId, preferred) } ?: moves.firstOrNull() ?: all.firstOrNull()
    }

    private fun branch(worker: NativeBranchWorker, frame: NativeBattleFrame, ally: BattleActionCandidate, foe: BattleActionCandidate, seed: Int): NativeBattleFrame {
        val start = if (seed == 0) frame.snapshotJson else worker.reseed(frame.snapshotJson, seed).snapshotJson
        return worker.branch(start, NativeShowdownChoiceEncoder.encode(ally, BattleSide.ALLY, frame),
            NativeShowdownChoiceEncoder.encode(foe, BattleSide.OPPONENT, frame))
    }

    private fun gone(team: List<NativePokemonFrame>, active: List<NativePokemonFrame>, id: String): Boolean =
        (team.firstOrNull { it.uuid == id }?.hp ?: 0) <= 0 || active.none { it.uuid == id }

    private fun same(a: String?, b: String?): Boolean = a != null && b != null && PublicIds.canonical(a) == PublicIds.canonical(b)
}
