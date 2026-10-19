package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.outcome.PublicActionOutcomeProjector
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * What a Pokemon is worth, for the board's material and every knockout price ([jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth]),
 * and what the user's own knockout costs (Explosion, Final Gambit, recoil it cannot survive) at that worth.
 *
 * A Pokemon's worth is what it does in the fight plus what it still does before it falls, each side's mean held at one
 * Pokemon (user decision 2026-10-18: a knockout always takes the other side a piece closer to losing; the worth only
 * shares that piece out within a side):
 * - its fight, `W`: the mean of `(score + 1) / 2` over its one-on-ones ([PokemonMatchupScore.score], current HP and
 *   stages) against every living Pokemon of the other side;
 * - its next action, `A`: its best single hit at whoever it faces next (the other side's Pokemon in front when it is in
 *   front, any of the other side's living when it waits), the HP it takes plus the knockout at that Pokemon's `W`,
 *   times the chance it gets to use it: it moves first, or it survives the other's first hit. A hit that faints its
 *   user (Explosion) costs the user's own `W`. Without the chance a Lucario the boosted Mega Tyranitar knocks out first
 *   read as about to knock the Mega out with High Jump Kick (A 4.44, V 2.60 of a side mean of 2); without the cost a
 *   benched Golem's Explosion read as a free knockout (2026-10-18).
 *
 * On the fight alone a 1% Probopass, losing every exchange, was worth nothing to knock out, and a Swampert set up
 * Stealth Rock in front of it; normalised over both sides at once, the one opposing Pokemon seen so far came out at
 * four Pokemon (measured 2026-10-18). Status moves are not part of `A`.
 */
internal object LocalKnockoutWorth {
    /** Each living Pokemon's worth in the units of [flat], the value of one average Pokemon of its side. */
    fun worths(state: BattleStateView, scores: MatchupScores, flat: Double): Map<UUID, Double> = read(state, scores, flat).worths

    data class Reading(
        /** Each living Pokemon's worth, in the units of the flat value asked for. */
        val worths: Map<UUID, Double>,
        /**
         * What knocking each one out this turn stops, in board units: its best hit at the other side's Pokemon in front,
         * landed or not as it would without being knocked out first (no acting chance, no cost to itself). A 1% Golem
         * the attacker outspeeds is worth little to keep, but left standing it Explodes on whatever is in front.
         */
        val denials: Map<UUID, Denial>,
    )

    /** A hit knocking its user out this turn stops: its value in board units and the priority it would come at. */
    data class Denial(
        val value: Double,
        val priority: Int,
        /**
         * How much it matters that it falls now rather than later: the chance its best hit knocks out one in front this
         * turn (it acts), at least [MINIMUM_URGENCY], and whole once it has healed itself. A Pokemon that cannot hurt the one in front is knocked out as well
         * next turn: its whole worth credited to a knockout now, a +4 Defence Corviknight at 23% took Brave Bird
         * (its recoil left 15%, then the next foe knocked it out) over Roost (dev server, 2026-10-20).
         */
        val urgency: Double = 1.0,
        /** The chance its best hit knocks out one in front this turn, at its chance to act. */
        val threat: Double = 0.0,
    )

    const val MINIMUM_URGENCY = 0.25

    fun read(state: BattleStateView, scores: MatchupScores, flat: Double): Reading {
        val living = state.pokemon.filter { !it.fainted && it.hpFraction > 0.0 }
        val fight = living.mapNotNull { pokemon ->
            val values = living.filter { it.side != pokemon.side }.mapNotNull { other ->
                if (pokemon.side == BattleSide.ALLY) scores.pokemon(pokemon.battlePokemonId, other.battlePokemonId)?.score
                else scores.pokemon(other.battlePokemonId, pokemon.battlePokemonId)?.score?.let { -it }
            }
            if (values.isEmpty()) null else pokemon.battlePokemonId to values.map { (it + 1.0) / 2.0 }.average()
        }.toMap()
        if (fight.isEmpty()) return Reading(emptyMap(), emptyMap())
        val sides = fight.keys.associateWith { id -> living.first { it.battlePokemonId == id }.side }
        // Each side's values scaled to a mean of one Pokemon; a side whose values are all zero keeps one each.
        fun perSide(raw: Map<UUID, Double>): Map<UUID, Double> = raw.entries.groupBy { sides.getValue(it.key) }.values.flatMap { entries ->
            val mean = entries.map { it.value }.average()
            entries.map { (id, value) -> id to if (mean < MINIMUM_MEAN) 1.0 else value / mean }
        }.toMap()
        val w = perSide(fight)
        val action = fight.keys.associateWith { id ->
            val pokemon = living.first { it.battlePokemonId == id }
            val others = living.filter { it.side != pokemon.side }
            val faced = if (pokemon.activeSlot != null) others.filter { it.activeSlot != null }.ifEmpty { others } else others
            faced.map { target ->
                val best = scores.moves(id, target.battlePokemonId).maxOfOrNull { move ->
                    (move.accuracy * minOf((move.minimumDamageFraction + move.maximumDamageFraction) / 2.0, target.hpFraction) +
                        move.knockoutChanceWithin(1) * (w[target.battlePokemonId] ?: 1.0) * ONE_POKEMON -
                        move.selfKnockoutChance * w.getValue(id) * ONE_POKEMON) * actingChance(state, scores, pokemon, target, move)
                }?.coerceAtLeast(0.0) ?: 0.0
                best
            }.average()
        }
        val denials = fight.keys.associateWith { id ->
            val pokemon = living.first { it.battlePokemonId == id }
            val inFront = living.filter { it.side != pokemon.side && it.activeSlot != null }
            // Its best hit at each Pokemon in front, at the chance it acts this turn at all (asleep, frozen, paralysed).
            val hits = inFront.mapNotNull { target ->
                scores.moves(id, target.battlePokemonId).map { move ->
                    move to move.accuracy * minOf((move.minimumDamageFraction + move.maximumDamageFraction) / 2.0, target.hpFraction) +
                        move.knockoutChanceWithin(1) * (w[target.battlePokemonId] ?: 1.0) * ONE_POKEMON
                }.maxByOrNull { it.second }
            }
            val threat = inFront.maxOfOrNull { target ->
                scores.moves(id, target.battlePokemonId).maxOfOrNull { it.accuracy * it.knockoutChanceWithin(1) } ?: 0.0
            } ?: 0.0
            // One that has healed itself in this battle (an ability, an item, a draining or healing move) may not be in reach
            // later: knocking it out now matters whole. Read on threat alone, a Milotic left a Poison Heal Gliscor it Ice
            // Beamed four times over at a quarter of the knockout and Scalded instead (hand-played Cynthia, 2026-10-20).
            // Its own heal only: a terrain heals every Pokemon on the field (Grassy Terrain read a Mimikyu as a self-healer).
            // A rise straight after a hit to the same Pokemon is the HP bar settling on that hit (+0.7% after a Brave Bird
            // read a Mimikyu as a self-healer and a 23% Corviknight took the knockout over Roost, 2026-10-20).
            val heals = state.observedEvents.withIndex().any { (index, event) ->
                val before = state.observedEvents.getOrNull(index - 1)
                event.actorPokemonId == id && (event.hpFractionDelta ?: 0.0) > 0.0 &&
                    event.kind == jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind.HP_CHANGED &&
                    event.publicSourceEffectId?.let(PublicIds::canonical)?.contains("terrain") != true &&
                    !(before?.kind == jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind.HP_CHANGED &&
                        before.actorPokemonId == id && (before.hpFractionDelta ?: 0.0) < 0.0)
            }
            val threatNow = (threat * actsThisTurn(pokemon)).coerceIn(0.0, 1.0)
            Denial(hits.map { it.second }.averageOrZero() * actsThisTurn(pokemon), hits.maxByOrNull { it.second }?.first?.priority ?: 0,
                if (heals) 1.0 else threatNow.coerceAtLeast(MINIMUM_URGENCY), threatNow)
        }
        // Fight and next action in board units (an HP bar is one, a Pokemon two), then scaled per side again.
        return Reading(perSide(fight.keys.associateWith { (w.getValue(it) * ONE_POKEMON) + action.getValue(it) })
            .mapValues { (_, value) -> flat * value }, denials)
    }

    private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()

    /** The chance [pokemon] acts this turn at all: not recharging, awake, thawed, not fully paralysed. */
    internal fun actsThisTurn(pokemon: BattlePokemonStateView): Double =
        LocalSetupGate.actingChance(pokemon) *
            if (PublicIds.canonical(pokemon.statusId.orEmpty()) in PARALYSED) LocalMatchupScoreCalculator.PARALYSIS_ACTS else 1.0

    private val PARALYSED = setOf("par", "paralysis")

    /**
     * The chance [subject] gets [move] off against [other]: it moves first, or it survives [other]'s first hit. Who
     * moves first is read from [move]'s priority against the hit [other] uses in their exchange, by Speed when they
     * tie: the exchange's own order is for the move it picked, and a slow Pokemon's priority move is not its exchange
     * move (Codex review of 5ee63ad6).
     */
    private fun actingChance(
        state: BattleStateView,
        scores: MatchupScores,
        subject: BattlePokemonStateView,
        other: BattlePokemonStateView,
        move: MoveMatchupScore,
    ): Double {
        val ally = subject.side == BattleSide.ALLY
        val pair = (if (ally) scores.pokemon(subject.battlePokemonId, other.battlePokemonId)
            else scores.pokemon(other.battlePokemonId, subject.battlePokemonId)) ?: return 1.0
        val reply = (if (ally) pair.opponentMove else pair.subjectMove) ?: return 1.0
        val first = when {
            move.priority > reply.priority -> 1.0
            move.priority < reply.priority -> 0.0
            else -> jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.speedOrderProbability(state, subject, other) ?: 0.5
        }
        return (first + (1.0 - first) * (1.0 - reply.knockoutChanceWithin(1))).coerceIn(0.0, 1.0)
    }

    fun adjustments(
        candidates: List<BattleActionCandidate>,
        ranked: BattleDecisionContext,
        board: BattleStateView,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val flat = tuning.knockoutMaterialScore
        if (flat <= 0.0 || tuning.legacyRawPowerFallback) return emptyMap()
        val worth = worths(board, scores, flat)
        if (worth.isEmpty()) return emptyMap()
        val memo = HashMap<String, Double>()
        return candidates.mapNotNull { candidate ->
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            val total = parts.sumOf { part -> memo.getOrPut(part.actionId) { difference(part, ranked, worth, flat, tuning) } }
            if (total == 0.0) null else candidate.actionId to total
        }.toMap()
    }

    private fun difference(
        move: BattleActionCandidate,
        ranked: BattleDecisionContext,
        worth: Map<UUID, Double>,
        flat: Double,
        tuning: LocalDecisionTuning,
    ): Double {
        if (move.kind != BattleActionKind.USE_MOVE) return 0.0
        val state = ranked.state
        fun at(side: BattleSide, slot: Int?) = state.pokemon.firstOrNull { it.side == side && it.activeSlot == slot && !it.fainted }
        fun scale(pokemon: BattlePokemonStateView?) = pokemon?.let { worth[it.battlePokemonId] }?.let { it / flat - 1.0 } ?: 0.0
        // An opposing Pokemon's knockout is priced at its worth where the ranking credits it (LocalPokemonWorth); the
        // user's own is charged flat by the outcome evaluator, so it is moved here.
        var difference = 0.0
        // The user's own knockout (Explosion, Final Gambit, recoil it cannot survive), as the outcome evaluator charged it.
        if (move.moveDetails?.effects?.effects.orEmpty().any { it.target == BattleMoveEffectTarget.USER && it.kind in SELF_COST }) {
            val user = at(BattleSide.ALLY, move.actorSlot)
            val projection = PublicActionOutcomeProjector.project(move, ranked)
            val fall = maxOf(
                projection.expectedSelfFaintChance,
                if ((projection.expectedSelfRecoilFraction ?: 0.0) > 0.0 && projection.actorExpectedHpAfterSelfEffects == 0.0) 1.0 else 0.0,
            )
            difference -= flat * fall * scale(user)
        }
        return difference
    }

    private val SELF_COST = setOf(
        BattleMoveEffectKind.SELF_DESTRUCT, BattleMoveEffectKind.RECOIL_FRACTION, BattleMoveEffectKind.MAX_HP_RECOIL,
        BattleMoveEffectKind.CRASH_RECOIL, BattleMoveEffectKind.STRUGGLE_RECOIL,
    )
    private const val MINIMUM_MEAN = 1e-6
    /** One Pokemon in board units, as [jbro.cobblemon.mcc.betterai.evaluation.LocalBoardMaterial] counts it. */
    private const val ONE_POKEMON = 2.0
}
