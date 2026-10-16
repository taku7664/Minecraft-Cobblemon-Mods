package jbro.cobblemon.mcc.betterai.matchup

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
 * their expected damage is capped at the user's remaining HP once and their largest one-hit knockout chance is used,
 * so two threats do not award the same knockout twice.
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
            val plain = threat(candidateContext, state, user, foes, targeting, tuning, cache) ?: return@mapNotNull null
            val guarded = threat(candidateContext, with(state, dynamaxed), dynamaxed, foes, targeting, tuning, cache)
                ?: return@mapNotNull null
            (plain - guarded).takeIf { it > 0.0 }?.let { candidate.actionId to it }
        }.toMap()
        return Reading(
            adjustments,
            targetFallbacks = targetingByUser.values.sumOf { it.fallbackOpponentIds.size },
            targetAssessments = targetingByUser.size * foes.size,
        )
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
    ): Double? {
        val hits = foes.mapNotNull { foe ->
            val pair = LocalMatchupScoreCalculator.pairMatchup(
                context.copy(state = state), user.battlePokemonId, foe.battlePokemonId,
                MatchupSpeedField.CURRENT, cache,
            ) ?: return@mapNotNull null
            val hit = pair.opponentMove ?: return@mapNotNull null
            val acts = 1.0 - pair.subjectMovesFirstProbability * (pair.subjectMove?.knockoutChanceWithin(1) ?: 0.0)
            IncomingHit(hit, acts.coerceIn(0.0, 1.0), targeting.byOpponent.getValue(foe.battlePokemonId))
        }
        if (hits.isEmpty()) return null
        // Every hit is already halved for a Dynamaxed target, as every calculation reads one.
        val taken = hits.sumOf {
            (it.move.minimumDamageFraction + it.move.maximumDamageFraction) / 2.0 * it.move.accuracy *
                it.incomingProbability * it.targetProbability
        }
        val knockout = hits.maxOf {
            it.move.knockoutChanceWithin(1) * it.incomingProbability * it.targetProbability
        }
        return minOf(taken, user.hpFraction) * tuning.boardToScore + knockout * tuning.knockoutMaterialScore
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

    private data class IncomingHit(
        val move: MoveMatchupScore,
        val incomingProbability: Double,
        val targetProbability: Double,
    )

    private data class Targeting(val byOpponent: Map<UUID, Double>, val fallbackOpponentIds: Set<UUID>)

    private val SPREAD_TARGETS = setOf(
        BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ADJACENT,
        BattleMoveTargetPattern.ALL_ACTIVE,
    )
}
