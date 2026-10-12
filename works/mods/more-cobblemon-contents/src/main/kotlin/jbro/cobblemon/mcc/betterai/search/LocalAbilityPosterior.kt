package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.simulation.NativeItemRebinding
import jbro.cobblemon.mcc.betterai.simulation.NativeMoveSetRebinding
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeTurnTrace
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * The abilities the turn just played rules out for the opposing Pokemon in front (singles). One engine world per
 * candidate ability, the same world in everything else, each plays the turn as it was played (this side's action, the
 * opponent's observed move) on [SALTS] random streams. Only what an ability can change is compared with what was
 * seen: the named sources of each side's HP changes (recoil, Life Orb, poison, Rough Skin), the stages and the
 * statuses; never the damage, which the hidden stats decide. An ability none of whose streams matches, while another
 * ability's does, is ruled out: Contrary or Simple by the stages, Rock Head by the recoil, Magic Guard by the residual
 * damage. No ability is named.
 *
 * [NativeProductSessionReconciler] was tried first: it compares the whole board, so a damage roll or a stat the
 * tier's spread assumption got wrong killed every world, the right ability's with them (2026-10-12).
 *
 * [open] at the end of a decision, with the action taken; [read] at the next one.
 */
internal object LocalAbilityPosterior {
    data class Pending(
        val subject: UUID,
        /** This side's Pokemon in front when the worlds were built. */
        val ally: UUID,
        val roots: Map<String, NativeBattleFrame>,
        val taken: BattleActionCandidate,
        val knownMoves: Set<String>,
        val lastEventSequence: Long?,
    ) {
        val abilities: Set<String> get() = roots.keys
    }

    data class Reading(val status: String, val ruledOut: Set<String>?, val detail: String? = null, val departure: Departure? = null)

    /** What leaving the field left [subject] with under each ability, to compare when it comes back (Regenerator). */
    data class Departure(val subject: UUID, val afterSequence: Long, val outcomes: Map<String, jbro.cobblemon.mcc.betterai.simulation.NativeDepartureOutcome>)

    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> 1 })
    private const val SALTS = 8
    /** The public HP share is a rounded percentage. */
    private const val HP_TOLERANCE = 0.02

    fun open(context: BattleDecisionContext, tier: BattleTrainerTier, taken: BattleActionCandidate): Pending? {
        val state = context.state
        if (state.format != BattleFormat.SINGLE) return null
        val foe = state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val ally = state.pokemon.singleOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        if (foe.knownAbilityId != null) return null
        val abilities = LocalEntryAbilityProjector.publicPossibleAbilities(state, foe)
        if (abilities.size < 2) return null
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            state.turn in 0..1 && jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules.acceptsObservations(state) ->
                planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        val world = plan.worlds.firstOrNull() ?: return null
        val uuid = foe.battlePokemonId.toString()
        if (world.definition.p2Team.none { it.uuid == uuid }) return null
        return LocalEngineStopProbe.lease.withWorker(deadline(context)) { worker ->
            Pending(
                subject = foe.battlePokemonId,
                ally = ally.battlePokemonId,
                roots = abilities.associateWith { ability ->
                    val definition = withAbility(world.definition, uuid, ability)
                    live?.let { worker.createLiveBattle(it, definition) } ?: worker.createBattle(definition)
                },
                taken = taken,
                knownMoves = foe.knownMoveIds.mapTo(hashSetOf(), PublicIds::canonical),
                lastEventSequence = state.observedEvents.lastOrNull()?.sequence,
            )
        }
    }

    fun read(pending: Pending, context: BattleDecisionContext): Reading {
        val state = context.state
        val window = state.observedEvents.filter { pending.lastEventSequence == null || it.sequence > pending.lastEventSequence }
        if (window.isEmpty()) return Reading("no_events", null)
        val opponents = state.pokemon.filter { it.side == BattleSide.OPPONENT }.mapTo(hashSetOf()) { it.battlePokemonId }
        // A knockout or an opposing switch ends the comparison: the replacement and its entry are another board.
        if (window.any { it.kind == BattleObservedEventKind.FAINTED }) return Reading("fainted", null)
        val opposingSwitch = window.firstOrNull { it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId in opponents }
        if (opposingSwitch != null) {
            // It left at the start of the turn, nothing done to it first: what leaving does under each ability is
            // read on the board it left from, for when it comes back.
            val left = state.pokemon.none { it.battlePokemonId == pending.subject && it.activeSlot != null }
            val touched = window.any { it.actorPokemonId == pending.subject && it.sequence < opposingSwitch.sequence &&
                (it.kind == BattleObservedEventKind.MOVE_USED || it.kind == BattleObservedEventKind.HP_CHANGED || it.kind == BattleObservedEventKind.STATUS_CHANGED) }
            if (!left || touched) return Reading("opponent_switched", null)
            val root = pending.roots.values.first()
            val outcomes = LocalEngineStopProbe.lease.withWorker(deadline(context)) { worker ->
                worker.departureOutcomes(root.snapshotJson, pending.subject.toString(), pending.abilities.toList())
            } ?: return Reading("no_worker", null)
            // An ability that would have said so on leaving, while it left in silence, is out at once.
            val silent = window.none { it.kind == BattleObservedEventKind.ABILITY_REVEALED && it.actorPokemonId == pending.subject }
            val announced = if (silent) outcomes.filterValues { it.announced }.keys else emptySet()
            return Reading("departed", announced.takeIf { it.size < outcomes.size }.orEmpty(),
                departure = Departure(pending.subject, opposingSwitch.sequence, outcomes.filterKeys { it !in announced }))
        }
        // This side switched out: an ability that would have held it in is out.
        val trapRuled = if (pending.taken.kind == BattleActionKind.SWITCH && window.any {
                it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == pending.taken.switchPokemonId
            }) {
            LocalEngineStopProbe.lease.withWorker(deadline(context)) { worker ->
                worker.trappingAbilities(pending.roots.values.first().snapshotJson, pending.subject.toString(), pending.ally.toString(), pending.abilities.toList())
            }.orEmpty().takeIf { it.size < pending.abilities.size }.orEmpty()
        } else emptySet()
        val replayed = replay(pending, context, window)
        if (trapRuled.isEmpty()) return replayed
        return replayed.copy(status = replayed.status + "_trap", ruledOut = replayed.ruledOut.orEmpty() + trapRuled)
    }

    /**
     * When [departure]'s Pokemon is back in front: the abilities whose departure leaves another HP share or status than
     * it came back with (before anything the turn did to it after its entry). Null while it has not come back.
     */
    fun arrival(departure: Departure, context: BattleDecisionContext): Set<String>? {
        val state = context.state
        val pokemon = state.pokemon.firstOrNull { it.battlePokemonId == departure.subject } ?: return emptySet()
        if (pokemon.activeSlot == null || pokemon.fainted) return null
        val entry = state.observedEvents.lastOrNull {
            it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == departure.subject && it.sequence > departure.afterSequence
        } ?: return emptySet()
        val after = state.observedEvents.filter { it.sequence > entry.sequence && it.actorPokemonId == departure.subject }
        val hpAtEntry = pokemon.hpFraction - after.filter { it.kind == BattleObservedEventKind.HP_CHANGED }.sumOf { it.hpFractionDelta ?: 0.0 }
        val statusKnown = after.none { it.kind == BattleObservedEventKind.STATUS_CHANGED }
        val status = PublicIds.canonical(pokemon.statusId.orEmpty())
        val fits = departure.outcomes.filterValues { outcome ->
            kotlin.math.abs(outcome.hpFraction - hpAtEntry) <= HP_TOLERANCE && (!statusKnown || PublicIds.canonical(outcome.status) == status)
        }.keys
        if (fits.isEmpty() || fits.size == departure.outcomes.size) return emptySet()
        return departure.outcomes.keys - fits
    }

    private fun replay(pending: Pending, context: BattleDecisionContext, window: List<jbro.cobblemon.mcc.internal.ai.BattleObservedEventView>): Reading {
        val state = context.state
        val used = window.firstOrNull { it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == pending.subject }
            ?.publicValueId?.let(PublicIds::canonical) ?: return Reading("no_opponent_move", null)
        val subject = state.pokemon.firstOrNull { it.battlePokemonId == pending.subject } ?: return Reading("subject_gone", null)
        // An item the turn revealed (Leftovers at its end, a Life Orb's recoil) goes into every world before the replay.
        val revealedItem = window.lastOrNull { it.kind == BattleObservedEventKind.HELD_ITEM_REVEALED && it.actorPokemonId == pending.subject }
            ?.publicValueId?.let(PublicIds::canonical)
        val allyNow = state.pokemon.singleOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted }
            ?: return Reading("ally_gone", null)
        val watched = listOf(subject, allyNow)
        val seenSources = watched.associate { pokemon ->
            pokemon.battlePokemonId.toString() to window.filter {
                it.kind == BattleObservedEventKind.HP_CHANGED && it.actorPokemonId == pokemon.battlePokemonId && it.publicSourceEffectId != null
            }.mapTo(hashSetOf()) { source(requireNotNull(it.publicSourceEffectId)) }
        }
        val seenBoosts = watched.associate { pokemon ->
            pokemon.battlePokemonId.toString() to pokemon.statStages.filterValues { it != 0 }.mapKeys { (name, _) -> stat(name) }
        }
        val seenStatus = watched.associate { it.battlePokemonId.toString() to PublicIds.canonical(it.statusId.orEmpty()) }
        fun matches(trace: NativeTurnTrace): Boolean = watched.all { pokemon ->
            val id = pokemon.battlePokemonId.toString()
            trace.hpSources[id].orEmpty().mapTo(hashSetOf(), ::source) == seenSources[id] &&
                trace.boosts[id].orEmpty().mapKeys { (name, _) -> stat(name) } == seenBoosts[id] &&
                PublicIds.canonical(trace.statuses[id].orEmpty()) == seenStatus[id]
        }
        var firstDiff: String? = null
        fun diff(trace: NativeTurnTrace): String = watched.joinToString(";") { pokemon ->
            val id = pokemon.battlePokemonId.toString()
            "${pokemon.speciesId}:src=${trace.hpSources[id].orEmpty().map(::source)}/${seenSources[id]}" +
                ",boost=${trace.boosts[id].orEmpty().mapKeys { (name, _) -> stat(name) }}/${seenBoosts[id]}" +
                ",st=${trace.statuses[id]}/${seenStatus[id]}"
        }
        val matched = LocalEngineStopProbe.lease.withWorker(deadline(context)) { worker ->
            pending.roots.mapValues { (_, root) ->
                val frame = withMove(worker, root, pending, used)?.let { withItem(worker, it, pending, revealedItem) } ?: return@mapValues null
                val allyChoice = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame, allowedMechanics = emptySet()).let { actions ->
                    actions.firstOrNull { it.actionId == pending.taken.actionId } ?: actions.firstOrNull {
                        it.kind == pending.taken.kind && it.mechanic == null && same(it.moveId, pending.taken.moveId) &&
                            it.switchPokemonId == pending.taken.switchPokemonId
                    }
                } ?: return@mapValues null
                val foeChoice = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame, allowedMechanics = emptySet())
                    .firstOrNull { it.kind == BattleActionKind.USE_MOVE && it.mechanic == null && same(it.moveId, used) } ?: return@mapValues null
                val p1 = NativeShowdownChoiceEncoder.encode(allyChoice, BattleSide.ALLY, frame)
                val p2 = NativeShowdownChoiceEncoder.encode(foeChoice, BattleSide.OPPONENT, frame)
                (0 until SALTS).any { salt -> worker.turnTrace(frame.snapshotJson, p1, p2, salt).let {
                    if (salt == 0 && firstDiff == null) firstDiff = "int=${it.interrupted}:" + diff(it)
                    !it.interrupted && matches(it)
                } }
            }
        } ?: return Reading("no_worker", null)
        if (matched.values.any { it == null }) return Reading("not_replayable", null)
        val alive = matched.filterValues { it == true }.keys
        if (alive.isEmpty()) return Reading("none_matched", null, firstDiff)
        return Reading("read", pending.roots.keys.filterTo(linkedSetOf()) { it !in alive })
    }

    /** [root] with the observed move in place of a hypothesised one when the world did not carry it. */
    private fun withMove(
        worker: jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker,
        root: NativeBattleFrame,
        pending: Pending,
        used: String,
    ): NativeBattleFrame? {
        val pokemon = root.p2Team.firstOrNull { it.uuid == pending.subject.toString() } ?: return null
        val moves = pokemon.moves.map { PublicIds.canonical(it.id) }
        if (used in moves) return root
        val slot = moves.indexOfLast { it !in pending.knownMoves }.takeIf { it >= 0 } ?: return null
        return worker.rebindMoves(root.snapshotJson, listOf(NativeMoveSetRebinding(pokemon.uuid, moves, moves.toMutableList().also { it[slot] = used })))
    }

    private fun withItem(
        worker: jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker,
        frame: NativeBattleFrame,
        pending: Pending,
        item: String?,
    ): NativeBattleFrame {
        if (item.isNullOrEmpty() || !worker.canRebindItems) return frame
        val held = frame.p2Team.firstOrNull { it.uuid == pending.subject.toString() }?.item?.let(PublicIds::canonical) ?: return frame
        if (held == item) return frame
        return worker.rebindItems(frame.snapshotJson, listOf(NativeItemRebinding(pending.subject.toString(), held, item)))
    }

    private fun withAbility(definition: NativeBattleDefinition, uuid: String, ability: String): NativeBattleDefinition = definition.copy(
        p2Team = definition.p2Team.map { if (it.uuid == uuid) it.copy(ability = ability) else it },
        openingState = definition.openingState?.let { opening ->
            opening.copy(pokemon = opening.pokemon.map { if (it.uuid == uuid) it.copy(ability = ability) else it })
        },
    )

    /** "item: Life Orb", "lifeorb" and "Recoil" alike. */
    private fun source(id: String): String = PublicIds.canonical(id.substringAfter(':'))

    /** The engine's and the public state's stat names on one spelling. */
    private fun stat(name: String): String = when (val id = PublicIds.canonical(name)) {
        "attack" -> "atk"
        "defense", "defence" -> "def"
        "specialattack" -> "spa"
        "specialdefense", "specialdefence" -> "spd"
        "speed" -> "spe"
        else -> id
    }

    private fun same(a: String?, b: String?) = a != null && b != null && PublicIds.canonical(a) == PublicIds.canonical(b)

    private fun deadline(context: BattleDecisionContext) = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
        (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
}
