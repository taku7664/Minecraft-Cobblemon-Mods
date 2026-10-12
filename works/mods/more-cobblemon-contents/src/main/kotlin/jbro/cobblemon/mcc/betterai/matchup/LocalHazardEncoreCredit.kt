package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.PublicSwitchEntryHazardCalculator
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Stealth Rock, Spikes and Encore, in the root's units, for moves whose worth lies past the two turns the engine plays.
 *
 * The engine's two turns could not price either: a hazard pays on entries that come later, and Encore pays in the turns
 * the target stays locked. Both took the generic status value (20) and the engine's short reading, so Stealth Rock with
 * three opponents to come lost to a single 40% hit and Encore was never worth a turn. These rules replace that reading
 * (LocalNativeStatusValue leaves the moves priced here alone).
 *
 * Which moves these are is read by ID (Stealth Rock, Spikes, Encore): a mechanic branch, so hard-coded.
 */
internal object LocalHazardEncoreCredit {
    /** Moves these rules price; the engine's status reading leaves them alone. */
    fun prices(candidate: BattleActionCandidate): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE && candidate.moveId?.let(PublicIds::canonical) in PRICED

    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
    ): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var change = 0.0
            for (part in parts) {
                if (part.kind != BattleActionKind.USE_MOVE) continue
                when (part.moveId?.let(PublicIds::canonical)) {
                    in HAZARDS -> hazard(part, context, scores)?.let { change += it - GENERIC_STATUS_VALUE }
                    ENCORE -> change += encore(part, context, scores) - GENERIC_STATUS_VALUE
                }
            }
            if (change != 0.0) out[candidate.actionId] = change
        }
        return out
    }

    /**
     * A hazard layer: every opponent left loses its entry ([PublicSwitchEntryHazardCalculator], the game's fractions),
     * weighted up by how well it sweeps this side ([SweepScore.score]), plus a share for each opponent left. Nothing
     * when only the Pokemon in front is left or the layer would not stack. A revealed hazard remover halves it.
     */
    private fun hazard(move: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Double? {
        val state = context.state
        val id = move.moveId?.let(PublicIds::canonical) ?: return null
        val current = state.field.sideConditions.getValue(BattleSide.OPPONENT)
        val stacks = current.firstOrNull { PublicIds.canonical(it.effectId) == id }?.stacks ?: 0
        if (stacks >= (MAX_LAYERS[id] ?: 1)) return null
        val remaining = state.remainingPokemonBySide.getValue(BattleSide.OPPONENT)
        val active = state.pokemon.count { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        if (remaining - active <= 0) return 0.0
        val withLayer = BattleFieldStateView(state.field.weather, state.field.terrain, state.field.roomEffects, state.field.globalEffects,
            state.field.sideConditions + (BattleSide.OPPONENT to (current.filter { PublicIds.canonical(it.effectId) != id } +
                BattleTimedEffectView(id, null, stacks = stacks + 1))))
        fun gain(pokemon: BattlePokemonStateView): Double {
            val before = PublicSwitchEntryHazardCalculator.hpLoss(state.field, BattleSide.OPPONENT, pokemon) ?: 0.0
            val after = PublicSwitchEntryHazardCalculator.hpLoss(withLayer, BattleSide.OPPONENT, pokemon) ?: NEUTRAL_ENTRY
            return (after - before).coerceAtLeast(0.0)
        }
        val seen = state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        val threats = seen.associate { it.battlePokemonId to (scores.sweeps[it.battlePokemonId]?.score ?: 0.0) }
        val meanThreat = threats.values.takeIf { it.isNotEmpty() }?.average() ?: UNSEEN_THREAT
        var value = seen.sumOf { foe -> gain(foe) * (1.0 + THREAT_WEIGHT * (threats[foe.battlePokemonId] ?: 0.0)) }
        // The rest of the team, not seen yet: the preview's unseen Pokemon on average, else a neutral entry.
        val unseen = (remaining - seen.size).coerceAtLeast(0)
        if (unseen > 0) {
            val seenSpecies = state.pokemon.filter { it.side == BattleSide.OPPONENT }.map { PublicIds.canonical(it.speciesId) }.toSet()
            val previewGains = context.opponentTeamPreview?.pokemon.orEmpty()
                .filter { PublicIds.canonical(it.speciesId) !in seenSpecies && it.knownTypeIds.isNotEmpty() }
                .map { slot ->
                    gain(BattlePokemonStateView(UUID.nameUUIDFromBytes("preview:${slot.previewSlotId}".toByteArray()),
                        BattleSide.OPPONENT, null, slot.speciesId, slot.formId, slot.level, 1.0, null, emptyMap(), emptySet(),
                        null, null, false, slot.knownTypeIds))
                }
            val perUnseen = previewGains.takeIf { it.isNotEmpty() }?.average() ?: NEUTRAL_ENTRY
            value += unseen * perUnseen * (1.0 + THREAT_WEIGHT * meanThreat)
        }
        val removerShown = seen.any { foe ->
            context.publicActionCatalog.forPokemon(foe.battlePokemonId).any { PublicIds.canonical(it.moveId) in HAZARD_REMOVAL } ||
                foe.knownMoveIds.any { PublicIds.canonical(it) in HAZARD_REMOVAL }
        }
        val total = value * BOARD_TO_SCORE + COUNT_BONUS * remaining
        return if (removerShown) total * REMOVER_DISCOUNT else total
    }

    /**
     * Encore locks the target into its last move. Worth the turns of that lock when the move does not hurt this side
     * and does not raise the target's own stats: this side's best expected hit on it each locked turn. Locking an
     * attack, a stat raise, or a target with no move to lock into is avoided outright.
     */
    private fun encore(move: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Double {
        val state = context.state
        val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == move.actorSlot && !it.fainted }
            ?: return AVOIDED
        val target = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return AVOIDED
        val events = state.observedEvents.filter { it.actorPokemonId == target.battlePokemonId }
        val sinceEntry = events.lastOrNull { it.kind == BattleObservedEventKind.SWITCHED }?.sequence ?: Long.MIN_VALUE
        val lastMove = events.lastOrNull { it.kind == BattleObservedEventKind.MOVE_USED && it.sequence > sinceEntry }
            ?.publicValueId?.let(PublicIds::canonical) ?: return AVOIDED
        val details = context.publicActionCatalog.forPokemon(target.battlePokemonId)
            .firstOrNull { PublicIds.canonical(it.moveId) == lastMove }?.details ?: return AVOIDED
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return AVOIDED
        val raisesItself = details.effects?.effects.orEmpty().any { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.values.any { it > 0 }
        }
        if (raisesItself) return AVOIDED
        val hit = scores.moves(user.battlePokemonId, target.battlePokemonId)
            .maxOfOrNull { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy } ?: 0.0
        return hit * ENCORE_TURNS * BOARD_TO_SCORE
    }

    private const val ENCORE = "encore"
    private val HAZARDS = setOf("stealthrock", "spikes")
    private val PRICED = HAZARDS + ENCORE
    private val MAX_LAYERS = mapOf("stealthrock" to 1, "spikes" to 3)
    /** Moves that clear hazards (the same set LocalIdleUtilityMoveRules reads). */
    private val HAZARD_REMOVAL = setOf("defog", "rapidspin", "mortalspin", "tidyup", "courtchange")
    /** Board value (one HP bar) to the root's units. */
    private const val BOARD_TO_SCORE = 100.0
    /** One entry for a Pokemon of no known type: Stealth Rock or a first Spikes layer. */
    private const val NEUTRAL_ENTRY = 1.0 / 8.0
    /** How much a sweeping opponent raises the worth of its entry damage (a full sweeper doubles it). */
    private const val THREAT_WEIGHT = 1.0
    /** The sweep reading for opponents not seen yet when none has been seen. */
    private const val UNSEEN_THREAT = 0.5
    /** Root points per opponent left: each is another entry to come. */
    private const val COUNT_BONUS = 5.0
    /** A revealed remover can take the hazard away. */
    private const val REMOVER_DISCOUNT = 0.5
    /** Turns Encore locks its target after this one. */
    private const val ENCORE_TURNS = 3.0
    /** Locking an attack or a stat raise: never chosen. */
    private const val AVOIDED = -999.0
    /** What the root already gives any status move (LocalNonDamagingMoveEvaluator's generic value). */
    private const val GENERIC_STATUS_VALUE = 20.0
}
