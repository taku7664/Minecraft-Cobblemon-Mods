package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
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
 * Hazard moves are read from the move data (a side condition on the target's side that is a hazard); Ceaseless Edge and
 * Stone Axe set theirs in a callback and are named, as is Encore: hard-coded.
 */
internal object LocalHazardEncoreCredit {
    /** Status moves these rules price; the engine's status reading leaves them alone. */
    fun prices(candidate: BattleActionCandidate): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE && (hazardOf(candidate) != null &&
            candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS ||
            candidate.moveId?.let(PublicIds::canonical) == ENCORE)

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
                // A status move already holds the generic status value; an attack keeps its own value and the layer adds.
                val held = if (part.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) GENERIC_STATUS_VALUE else 0.0
                val hazard = hazardOf(part)
                when {
                    hazard != null -> hazard(hazard, context, scores)?.let { change += it - held }
                    part.moveId?.let(PublicIds::canonical) == ENCORE -> change += encore(part, context, scores) - held
                }
            }
            if (change != 0.0) out[candidate.actionId] = change
        }
        return out
    }

    /** The hazard this move lays on the opponent's side: declared by its data, else set in a callback (named). */
    private fun hazardOf(move: BattleActionCandidate): String? {
        move.moveDetails?.effects?.effects.orEmpty().firstNotNullOfOrNull { effect ->
            effect.valueId?.let(PublicIds::canonical)?.takeIf {
                effect.kind == BattleMoveEffectKind.SIDE_CONDITION && effect.target == BattleMoveEffectTarget.TARGET_SIDE &&
                    it in MAX_LAYERS
            }
        }?.let { return it }
        return CALLBACK_HAZARDS[move.moveId?.let(PublicIds::canonical)]
    }

    /**
     * A hazard layer: the mean over the opponents left of what each loses on entry, weighted up by how well it sweeps
     * this side ([SweepScore.score]), times the opponents left to come in. HP hazards take the game's fractions
     * ([PublicSwitchEntryHazardCalculator]); Toxic Spikes the status it leaves; Sticky Web a flat share. Nothing when only
     * the Pokemon in front is left (it cannot switch) or the layer would not stack. A revealed hazard remover halves it.
     */
    private fun hazard(id: String, context: BattleDecisionContext, scores: MatchupScores): Double? {
        val state = context.state
        val current = state.field.sideConditions.getValue(BattleSide.OPPONENT)
        val stacks = current.firstOrNull { PublicIds.canonical(it.effectId) == id }?.stacks ?: 0
        if (stacks >= (MAX_LAYERS[id] ?: 1)) return null
        val remaining = state.remainingPokemonBySide.getValue(BattleSide.OPPONENT)
        val active = state.pokemon.count { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        // No one left to come in: the move fails outright (LocalSideConditionFutility) and earns nothing here.
        if (remaining - active <= 0) return 0.0
        val withLayer = BattleFieldStateView(state.field.weather, state.field.terrain, state.field.roomEffects, state.field.globalEffects,
            state.field.sideConditions + (BattleSide.OPPONENT to (current.filter { PublicIds.canonical(it.effectId) != id } +
                BattleTimedEffectView(id, null, stacks = stacks + 1))))
        fun entry(pokemon: BattlePokemonStateView): Double = when (id) {
            TOXIC_SPIKES -> if (!grounded(pokemon) || pokemon.statusId != null ||
                pokemon.knownTypeIds.any { PublicIds.canonical(it) in TOXIC_IMMUNE_TYPES }) 0.0
                else if (stacks + 1 >= 2) BADLY_POISONED_VALUE - (if (stacks >= 1) POISONED_VALUE else 0.0) else POISONED_VALUE
            STICKY_WEB -> if (grounded(pokemon)) STICKY_WEB_VALUE else 0.0
            else -> {
                val before = PublicSwitchEntryHazardCalculator.hpLoss(state.field, BattleSide.OPPONENT, pokemon) ?: 0.0
                val after = PublicSwitchEntryHazardCalculator.hpLoss(withLayer, BattleSide.OPPONENT, pokemon) ?: NEUTRAL_ENTRY
                (after - before).coerceAtLeast(0.0)
            }
        }
        val seen = state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        val threats = seen.associate { it.battlePokemonId to (scores.sweeps[it.battlePokemonId]?.score ?: 0.0) }
        val meanThreat = threats.values.takeIf { it.isNotEmpty() }?.average() ?: UNSEEN_THREAT
        // Each opponent left: its entry, weighted up by how well it sweeps this side. Their mean is the layer's worth per
        // Pokemon; how many are left enters once, as the multiplier below (2026-10-12: no sum over them as well).
        val perFoe = seen.map { foe -> entry(foe) * (1.0 + THREAT_WEIGHT * (threats[foe.battlePokemonId] ?: 0.0)) }.toMutableList()
        // The rest of the team, not seen yet: the preview's unseen Pokemon on average, else a neutral entry.
        val unseen = (remaining - seen.size).coerceAtLeast(0)
        if (unseen > 0) {
            val seenSpecies = state.pokemon.filter { it.side == BattleSide.OPPONENT }.map { PublicIds.canonical(it.speciesId) }.toSet()
            val previewEntries = context.opponentTeamPreview?.pokemon.orEmpty()
                .filter { PublicIds.canonical(it.speciesId) !in seenSpecies && it.knownTypeIds.isNotEmpty() }
                .map { slot ->
                    entry(BattlePokemonStateView(UUID.nameUUIDFromBytes("preview:${slot.previewSlotId}".toByteArray()),
                        BattleSide.OPPONENT, null, slot.speciesId, slot.formId, slot.level, 1.0, null, emptyMap(), emptySet(),
                        null, null, false, slot.knownTypeIds))
                }
            val perUnseen = previewEntries.takeIf { it.isNotEmpty() }?.average() ?: NEUTRAL_ENTRY
            repeat(unseen) { perFoe += perUnseen * (1.0 + THREAT_WEIGHT * meanThreat) }
        }
        val value = perFoe.average()
        val removerShown = seen.any { foe ->
            context.publicActionCatalog.forPokemon(foe.battlePokemonId).any { PublicIds.canonical(it.moveId) in HAZARD_REMOVAL } ||
                foe.knownMoveIds.any { PublicIds.canonical(it) in HAZARD_REMOVAL }
        }
        // Times the opponents that can still come in: none left, nothing (2026-10-12).
        val total = value * LAYER_SCALE * (remaining - 1)
        return if (removerShown) total * REMOVER_DISCOUNT else total
    }

    private fun grounded(pokemon: BattlePokemonStateView): Boolean =
        pokemon.knownTypeIds.none { PublicIds.canonical(it) == "flying" } &&
            pokemon.knownAbilityId?.let(PublicIds::canonical) != "levitate" &&
            pokemon.knownHeldItemId?.let(PublicIds::canonical) != "airballoon"

    /**
     * Encore locks the target into its last move. Worth the turns of that lock when the move does not hurt this side:
     * this side's best expected hit on it each locked turn. A stat raise counts only when those hits knock it out within
     * the lock ([lockedRaise]). Locking an attack, or a target with no move to lock into, is avoided outright.
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
        val raise = details.effects?.effects.orEmpty().filter { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER
        }.fold(mutableMapOf<String, Int>()) { stages, effect ->
            effect.statStages.forEach { (stat, delta) -> stages.merge(stat, delta, Int::plus) }
            stages
        }
        // Only a user that moves first locks the move just seen; a slower one locks whatever the target uses this turn.
        // Below even odds it is avoided; above, the lock's worth is weighed by the chance it moves first.
        val first = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, move.actorSlot, move, opponentPriority = 0)
            ?: return AVOIDED
        if (first < 0.5) return AVOIDED
        if (raise.values.any { it > 0 }) {
            return lockedRaise(user, target, raise, context, scores)?.let { first * it * BOARD_TO_SCORE } ?: AVOIDED
        }
        val hit = scores.moves(user.battlePokemonId, target.battlePokemonId)
            .maxOfOrNull { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy } ?: 0.0
        return first * hit * ENCORE_TURNS * BOARD_TO_SCORE
    }

    /**
     * A stat raise locked in: the target raises again on every locked turn and walks out boosted, so the lock is worth
     * this side's hits only when they knock it out first. The hits, as one HP bar, or null when they fall short.
     *
     * The target raises on the Encore turn and on the two locked turns after it; this side, moving first, hits on each
     * of those two and once more before the freed target acts, each hit against the defences raised so far (its
     * ability reshaping the raise: Contrary, Simple). A raise that includes Speed may let it act first once freed, so
     * then only the locked turns count. Read as never worth a turn, Encore stayed unused after a Swords Dance, the play
     * it is best known for (2026-10-13, decided with the user).
     */
    private fun lockedRaise(
        user: BattlePokemonStateView,
        target: BattlePokemonStateView,
        raise: Map<String, Int>,
        context: BattleDecisionContext,
        scores: MatchupScores,
    ): Double? {
        val gain = LocalStatStageChange.reshape(context.state, target, target.battlePokemonId, raise).entries
            .groupingBy { LocalStatStageChange.normalise(it.key) }.fold(0) { total, entry -> total + entry.value }
        val current = target.statStages.entries.associate { LocalStatStageChange.normalise(it.key) to it.value }
        val categories = (context.candidates.filter { it.actorSlot == user.activeSlot }.mapNotNull { candidate ->
            candidate.moveId?.let { id -> candidate.moveDetails?.let { PublicIds.canonical(id) to it.damageCategory } }
        } + context.publicActionCatalog.forPokemon(user.battlePokemonId).map { PublicIds.canonical(it.moveId) to it.details.damageCategory })
            .distinctBy { it.first }.toMap()
        val hits = if ((gain[SPEED] ?: 0) > 0) ENCORE_TURNS.toInt() - 1 else ENCORE_TURNS.toInt()
        var dealt = 0.0
        for (raised in 1..hits) {
            dealt += scores.moves(user.battlePokemonId, target.battlePokemonId).maxOfOrNull { hit ->
                // The defence the move meets; one of unknown kind meets whichever was raised more.
                val stats = when (categories[PublicIds.canonical(hit.moveId)]) {
                    BattleMoveDamageCategory.PHYSICAL -> listOf(DEFENCE)
                    BattleMoveDamageCategory.SPECIAL -> listOf(SPECIAL_DEFENCE)
                    else -> listOf(DEFENCE, SPECIAL_DEFENCE)
                }
                val scale = stats.minOf { stat ->
                    val now = current[stat] ?: 0
                    stageMultiplier(now) / stageMultiplier((now + raised * (gain[stat] ?: 0)).coerceIn(-6, 6))
                }
                (hit.minimumDamageFraction + hit.maximumDamageFraction) / 2.0 * hit.accuracy * scale
            } ?: 0.0
        }
        return dealt.takeIf { it >= target.hpFraction }
    }

    private fun stageMultiplier(stage: Int): Double = if (stage >= 0) (2.0 + stage) / 2.0 else 2.0 / (2.0 - stage)

    private const val ENCORE = "encore"
    private const val SPEED = "speed"
    private const val DEFENCE = "defence"
    private const val SPECIAL_DEFENCE = "special_defence"
    private const val TOXIC_SPIKES = "toxicspikes"
    private const val STICKY_WEB = "stickyweb"
    /** Hazards a layer of which this rule prices, with how many layers stack. */
    private val MAX_LAYERS = mapOf("stealthrock" to 1, "spikes" to 3, TOXIC_SPIKES to 2, STICKY_WEB to 1, "gmaxsteelsurge" to 1)
    /** Attacks that lay a hazard from a callback the move data does not declare (named: hard-coded). */
    private val CALLBACK_HAZARDS = mapOf("ceaselessedge" to "spikes", "stoneaxe" to "stealthrock")
    private val TOXIC_IMMUNE_TYPES = setOf("poison", "steel")
    /** Status burdens as the leaf weighs them (LocalImmediateTurnScorer.statusBurden). */
    private const val POISONED_VALUE = 0.20
    private const val BADLY_POISONED_VALUE = 0.35
    /** A Speed stage lost on entry, as the leaf's flat hazard share. */
    private const val STICKY_WEB_VALUE = 0.10
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
    /**
     * Root points for one opponent to come in with a mean entry of one full HP bar. Set on six positions (three, two and
     * six opponents left, Rock-weak and Rock-resistant benches): from 188 up every one comes out as expected, below 127
     * the six-Pokemon opening attacks instead of laying Stealth Rock. 2026-10-12.
     */
    private const val LAYER_SCALE = 200.0
    /** A revealed remover can take the hazard away. */
    private const val REMOVER_DISCOUNT = 0.5
    /** Turns Encore locks its target after this one. */
    private const val ENCORE_TURNS = 3.0
    /** Locking an attack, or a stat raise this side cannot knock out within the lock: never chosen. */
    private const val AVOIDED = -999.0
    /** What the root already gives any status move (LocalNonDamagingMoveEvaluator's generic value). */
    private const val GENERIC_STATUS_VALUE = 20.0
}
