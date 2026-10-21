package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * What Dynamaxing guards on the turn it starts: its doubled HP against the opposing Pokemon's best hit this turn, the
 * damage it halves and the knockout it stops, in score units. In doubles, both opposing active Pokemon contribute;
 * their expected damage is capped at the user's remaining HP once and the knockout is counted once, whether one hit
 * does it or the two together ([knockout]).
 *
 * The ranking prices a move by what it does to the target, so a Dynamax choice and the same move without it came out
 * equal whenever both knocked out (a Lapras Freeze-Dry at 300 either way), and the draw kept the one that left it to a
 * faster Max Move (G-948 ②). The hit guarded against is the one the opposing Pokemon has as it stands. Measured
 * against its Max Move too while its side might still Dynamax (the larger of the two), every lead Dynamaxed on turn 1
 * for the knockout that Max Move threatened, walls included (Torkoal at -32, Ferrothorn at -36); 2026-10-16.
 */
internal object LocalDynamaxGuard {
    data class Reading(
        val adjustments: Map<String, Double>,
        /** Unique opposing slots whose target probability used the neutral fallback. */
        val targetFallbacks: Int = 0,
        /** Unique opposing slots whose target probability was assessed. */
        val targetAssessments: Int = 0,
    )

    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
        intents: List<OpponentIntent>? = null,
    ): Map<String, Double> = read(candidates, context, tuning, intents).adjustments

    fun read(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
        intents: List<OpponentIntent>? = null,
    ): Reading {
        val state = context.state
        if (state.format !in setOf(BattleFormat.SINGLE, BattleFormat.DOUBLE)) return Reading(emptyMap())
        val dynamaxing = candidates.mapNotNull { candidate ->
            parts(candidate).singleOrNull { LocalDynamax.isMechanic(it.mechanic?.mechanicId) }?.let { candidate to it }
        }
        if (dynamaxing.isEmpty()) return Reading(emptyMap())
        val foes = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && alive(it) }
        if (foes.isEmpty()) return Reading(emptyMap())
        val cache = LocalProjectedActionCalculationCache()
        val targetingByUser = linkedMapOf<UUID, Targeting>()
        val adjustments = dynamaxing.mapNotNull { (candidate, mechanic) ->
            val user = state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == mechanic.actorSlot && alive(it)
            } ?: return@mapNotNull null
            if (LocalDynamax.active(user)) return@mapNotNull null
            val targeting = targetingByUser.getOrPut(user.battlePokemonId) { targeting(context, user, foes, intents) }
            val candidateContext = forCandidate(context, user, mechanic)
            val gigantamax = context.exactOwnTeam?.buildFor(user.battlePokemonId)?.gigantamax == true || LocalDynamax.gigantamax(user)
            val dynamaxed = LocalDynamax.start(user, gigantamax)
            // Whether each foe acts at all is the Dynamaxed candidate's: its Max Move knocking the foe out first is that
            // candidate's own value, which its ranking already holds. Read from the plain move instead, a Dynamax move that
            // knocked out where its plain move did not took the whole incoming hit as "guarded", so the weaker the move the
            // larger the guard (a Mamoswine Dynamaxed Ice Shard, +232, over Icicle Crash, +72; league run, 2026-10-19).
            // Only the doubled HP is guarded.
            val guardedState = with(state, dynamaxed)
            val acting = acting(candidateContext, guardedState, dynamaxed, foes, cache) { foe -> aims(mechanic, foe, state) }
            val plain = threat(candidateContext, state, user, foes, targeting, tuning, cache, acting) ?: return@mapNotNull null
            val guarded = threat(candidateContext, guardedState, dynamaxed, foes, targeting, tuning, cache, acting)
                ?: return@mapNotNull null
            // The HP kept is kept; the knockout stopped is only put off unless the doubled HP turns the one-on-one. Read whole, a 42% Araquanid that could not hurt a +2 Dondozo Dynamaxed to live two
            // turns longer (+216, hand-played Aaron), as had a 46% Luxray (hand-played Volkner; both 2026-10-20).
            val turns = foes.mapNotNull { foe ->
                val before = LocalMatchupScoreCalculator.pairMatchup(candidateContext.copy(state = state), user.battlePokemonId,
                    foe.battlePokemonId, MatchupSpeedField.CURRENT, cache)?.winProbability ?: return@mapNotNull null
                val after = LocalMatchupScoreCalculator.pairMatchup(candidateContext.copy(state = guardedState), user.battlePokemonId,
                    foe.battlePokemonId, MatchupSpeedField.CURRENT, cache)?.winProbability ?: return@mapNotNull null
                (after - before).coerceIn(0.0, 1.0)
            }.takeIf { it.isNotEmpty() }?.average() ?: 1.0
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("dynamaxGuard:${candidate.actionId}", mapOf("plainDamage" to plain.damage, "guardedDamage" to guarded.damage,
                "plainKnockout" to plain.knockout, "guardedKnockout" to guarded.knockout, "turns" to turns))
            ((plain.damage - guarded.damage) + (plain.knockout - guarded.knockout) * turns).takeIf { it > 0.0 }?.let { candidate.actionId to it }
        }.toMap()
        return Reading(
            adjustments,
            targetFallbacks = targetingByUser.values.sumOf { it.fallbackOpponentIds.size },
            targetAssessments = targetingByUser.size * foes.size,
        )
    }

    /** Per foe, the chance it acts this turn against the candidate on [state]: not knocked out first by its move. */
    private fun acting(
        context: BattleDecisionContext,
        state: BattleStateView,
        user: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        cache: LocalProjectedActionCalculationCache,
        /** Whether the candidate's move reaches this foe: a foe it does not aim at acts whatever the move does. */
        aimed: (BattlePokemonStateView) -> Boolean,
    ): Map<UUID, Double> = foes.mapNotNull { foe ->
        if (!aimed(foe)) return@mapNotNull foe.battlePokemonId to 1.0
        val pair = LocalMatchupScoreCalculator.pairMatchup(
            context.copy(state = state), user.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT, cache,
        ) ?: return@mapNotNull null
        foe.battlePokemonId to (1.0 - pair.subjectMovesFirstProbability * (pair.subjectMove?.knockoutChanceWithin(1) ?: 0.0)).coerceIn(0.0, 1.0)
    }.toMap()

    /**
     * Whether [part]'s move reaches [foe]: always in singles, and for a spread move; in doubles a single-target move only the
     * foe in the slot it names. A doubles Max Move read as knocking out both foes left neither acting (9da7b44f review).
     * A move without a named target is read as reaching every foe, as before.
     */
    private fun aims(part: BattleActionCandidate, foe: BattlePokemonStateView, state: BattleStateView): Boolean {
        if (state.format != BattleFormat.DOUBLE) return true
        if (part.moveDetails?.targetPattern in SPREAD_TARGETS) return true
        val target = part.targets.singleOrNull() ?: return true
        return target.side == foe.side && target.slot == foe.activeSlot
    }

    /** The opposing active Pokemon's best hits on [user] this turn, with material counted once, in score units. */
    private fun threat(
        context: BattleDecisionContext,
        state: BattleStateView,
        user: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        targeting: Targeting,
        tuning: LocalDecisionTuning,
        cache: LocalProjectedActionCalculationCache,
        /** Each foe's chance to act ([acting]), the same on both boards so only the hits' size differs. */
        acting: Map<UUID, Double>,
    ): Threat? {
        val hits = foes.mapNotNull { foe ->
            val pair = LocalMatchupScoreCalculator.pairMatchup(
                context.copy(state = state), user.battlePokemonId, foe.battlePokemonId,
                MatchupSpeedField.CURRENT, cache,
            ) ?: return@mapNotNull null
            val hit = pair.opponentMove ?: return@mapNotNull null
            val acts = acting[foe.battlePokemonId] ?: return@mapNotNull null
            IncomingHit(hit, acts, targeting.byOpponent.getValue(foe.battlePokemonId))
        }
        if (hits.isEmpty()) return null
        // Every hit is already halved for a Dynamaxed target, as every calculation reads one.
        val taken = hits.sumOf {
            (it.move.minimumDamageFraction + it.move.maximumDamageFraction) / 2.0 * it.move.accuracy *
                it.incomingProbability * it.targetProbability
        }
        return Threat(minOf(taken, user.hpFraction) * tuning.boardToScore, knockout(hits, user.hpFraction) * tuning.knockoutMaterialScore)
    }

    /** A turn's incoming hits in score units: the HP they take, and the knockout. */
    private class Threat(val damage: Double, val knockout: Double)

    /**
     * The chance [hits] knock the user out this turn. Two hits that each leave it standing can still do it together;
     * read one at a time, that knockout was never guarded (2026-10-18). Each hit lands with its aim, its turn and its accuracy;
     * a landed roll is even across its damage range, and a hit that knocks out alone keeps its own chance (critical
     * hits included). More than two hits fall back to the largest single knockout.
     */
    internal fun knockout(hits: List<IncomingHit>, hp: Double): Double {
        fun alone(hit: IncomingHit) = hit.move.knockoutChanceWithin(1) * hit.incomingProbability * hit.targetProbability
        if (hits.size != 2) return hits.maxOf(::alone)
        val (first, second) = hits
        fun lands(hit: IncomingHit) = (hit.incomingProbability * hit.targetProbability * hit.move.accuracy).coerceIn(0.0, 1.0)
        fun knocksOutOnLanding(hit: IncomingHit) =
            if (hit.move.accuracy <= 0.0) 0.0 else (hit.move.knockoutChanceWithin(1) / hit.move.accuracy).coerceIn(0.0, 1.0)
        // Super Fang and the like take a share of what is left, and Endeavor brings it down to its user's HP: what a
        // second hit does depends on the first, so a pair holding one adds nothing to the singles here.
        if (hits.any { PublicIds.canonical(it.move.moveId) in PublicBattleTacticalCalculator.TARGET_HP_SET_MOVES }) {
            return hits.maxOf(::alone)
        }
        val a = lands(first)
        val b = lands(second)
        val bothStanding = minOf(
            1.0 - knocksOutOnLanding(first),
            1.0 - knocksOutOnLanding(second),
            sumBelow(first.move, second.move, hp),
        )
        val survives = (1.0 - a) * (1.0 - b) +
            a * (1.0 - b) * (1.0 - knocksOutOnLanding(first)) +
            (1.0 - a) * b * (1.0 - knocksOutOnLanding(second)) +
            a * b * bothStanding
        return maxOf(1.0 - survives, hits.maxOf(::alone)).coerceIn(0.0, 1.0)
    }

    /** The chance two rolls, each even across its damage range, add up to less than [hp]. */
    private fun sumBelow(first: MoveMatchupScore, second: MoveMatchupScore, hp: Double): Double {
        fun below(move: MoveMatchupScore, limit: Double): Double {
            val low = move.minimumDamageFraction
            val high = move.maximumDamageFraction
            return when {
                limit <= low -> 0.0
                limit > high -> 1.0
                high <= low -> 0.0
                else -> (limit - low) / (high - low)
            }
        }
        val width = first.maximumDamageFraction - first.minimumDamageFraction
        if (width <= 0.0) return below(second, hp - first.minimumDamageFraction)
        return (0 until SUM_STEPS).sumOf { step ->
            below(second, hp - (first.minimumDamageFraction + width * (step + 0.5) / SUM_STEPS))
        } / SUM_STEPS
    }

    /** Restrict the user's matchup to the base move behind this candidate, so its own KO chance gates the reply. */
    private fun forCandidate(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        candidate: BattleActionCandidate,
    ): BattleDecisionContext {
        val moveId = LocalDynamax.baseMoveId(candidate)?.let(PublicIds::canonical) ?: return context
        val entry = context.publicActionCatalog.entries.singleOrNull { it.battlePokemonId == user.battlePokemonId } ?: return context
        val selected = entry.moves.filter { PublicIds.canonical(it.moveId) == moveId }
        if (selected.isEmpty()) return context
        val entries = context.publicActionCatalog.entries.map {
            if (it !== entry) it else BattlePokemonActionCatalogView(it.battlePokemonId, selected, moveSetComplete = true)
        }
        return context.copy(publicActionCatalog = BattlePublicActionCatalogView(
            entries,
            context.publicActionCatalog.originalEntries,
            context.publicActionCatalog.candidatePools,
            context.publicActionCatalog.opponentMoveInferences,
        ))
    }

    /** Predicted chance each foe actually aims a damaging move at [user]. */
    private fun targeting(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        intents: List<OpponentIntent>?,
    ): Targeting {
        if (context.state.format == BattleFormat.SINGLE) {
            return Targeting(foes.associate { it.battlePokemonId to 1.0 }, emptySet())
        }
        val alliedTargets = context.state.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && alive(it) }
            .coerceAtLeast(1)
        val fallback = 1.0 / alliedTargets
        val fallbacks = linkedSetOf<UUID>()
        val chances = foes.associate { foe ->
            val intent = intents?.singleOrNull { it.pokemonId == foe.battlePokemonId }
            var unresolved = intent == null || intent.options.isEmpty()
            var chance = 0.0
            intent?.options.orEmpty().filter { it.kind == IntentKind.ATTACK }.forEach { option ->
                when {
                    option.targetId == user.battlePokemonId -> chance += option.probability
                    option.targetId != null -> Unit
                    spread(context, foe.battlePokemonId, option.moveId) -> chance += option.probability
                    else -> unresolved = true
                }
            }
            foe.battlePokemonId to if (unresolved) {
                fallbacks += foe.battlePokemonId
                fallback
            } else chance.coerceIn(0.0, 1.0)
        }
        return Targeting(chances, fallbacks)
    }

    private fun spread(context: BattleDecisionContext, pokemonId: UUID, moveId: String?): Boolean {
        val canonical = moveId?.let(PublicIds::canonical) ?: return false
        val pattern = context.publicActionCatalog.forPokemon(pokemonId)
            .singleOrNull { PublicIds.canonical(it.moveId) == canonical }?.details?.targetPattern ?: return false
        return pattern in SPREAD_TARGETS
    }

    private fun with(state: BattleStateView, pokemon: BattlePokemonStateView): BattleStateView =
        state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == pokemon.battlePokemonId) pokemon else it })

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private fun alive(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0

    internal data class IncomingHit(
        val move: MoveMatchupScore,
        val incomingProbability: Double,
        val targetProbability: Double,
    )

    private data class Targeting(val byOpponent: Map<UUID, Double>, val fallbackOpponentIds: Set<UUID>)

    private const val SUM_STEPS = 64

    private val SPREAD_TARGETS = setOf(
        BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ADJACENT,
        BattleMoveTargetPattern.ALL_ACTIVE,
    )
}
