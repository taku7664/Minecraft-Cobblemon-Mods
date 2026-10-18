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
    fun worths(state: BattleStateView, scores: MatchupScores, flat: Double): Map<UUID, Double> {
        val living = state.pokemon.filter { !it.fainted && it.hpFraction > 0.0 }
        val fight = living.mapNotNull { pokemon ->
            val values = living.filter { it.side != pokemon.side }.mapNotNull { other ->
                if (pokemon.side == BattleSide.ALLY) scores.pokemon(pokemon.battlePokemonId, other.battlePokemonId)?.score
                else scores.pokemon(other.battlePokemonId, pokemon.battlePokemonId)?.score?.let { -it }
            }
            if (values.isEmpty()) null else pokemon.battlePokemonId to values.map { (it + 1.0) / 2.0 }.average()
        }.toMap()
        if (fight.isEmpty()) return emptyMap()
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
                    move.accuracy * minOf((move.minimumDamageFraction + move.maximumDamageFraction) / 2.0, target.hpFraction) +
                        move.knockoutChanceWithin(1) * (w[target.battlePokemonId] ?: 1.0) * ONE_POKEMON -
                        (if (move.selfKnockout) w.getValue(id) * ONE_POKEMON else 0.0)
                }?.coerceAtLeast(0.0) ?: 0.0
                best * actingChance(scores, pokemon, target)
            }.average()
        }
        // Fight and next action in board units (an HP bar is one, a Pokemon two), then scaled per side again.
        return perSide(fight.keys.associateWith { (w.getValue(it) * ONE_POKEMON) + action.getValue(it) })
            .mapValues { (_, value) -> flat * value }
    }

    /**
     * The chance [subject] gets its next action off against [other]: it moves first, or it survives [other]'s first hit.
     * Read from the pair table, keyed by the ally.
     */
    private fun actingChance(scores: MatchupScores, subject: BattlePokemonStateView, other: BattlePokemonStateView): Double {
        val ally = subject.side == BattleSide.ALLY
        val pair = (if (ally) scores.pokemon(subject.battlePokemonId, other.battlePokemonId)
            else scores.pokemon(other.battlePokemonId, subject.battlePokemonId)) ?: return 1.0
        val first = if (ally) pair.subjectMovesFirstProbability else 1.0 - pair.subjectMovesFirstProbability
        val knockedOut = (if (ally) pair.opponentMove else pair.subjectMove)?.knockoutChanceWithin(1) ?: 0.0
        return (first + (1.0 - first) * (1.0 - knockedOut)).coerceIn(0.0, 1.0)
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
