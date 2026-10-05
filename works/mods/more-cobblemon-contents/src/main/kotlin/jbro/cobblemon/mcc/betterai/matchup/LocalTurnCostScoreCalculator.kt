package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The scores for actions that spend a turn or take a hit before they act: [StatusMoveMatchupScore],
 * [SwitchInScore], and the team-level [PreserveScore] read from the same pair table.
 */
internal object LocalTurnCostScoreCalculator {
    /**
     * [user]'s status moves against [target], both on the field. Supported effects are the ones the
     * exchange can play out: a burn or paralysis on the target, certain stat drops on it, and stat raises
     * on the user. Sleep, poison chip and volatile effects change nothing the exchange reads, so those
     * moves are left unscored rather than scored as a wasted turn.
     */
    fun statusMoves(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        target: BattlePokemonStateView,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): List<StatusMoveMatchupScore> {
        val position = LocalMatchupPosition.face(context, user, target, cache) ?: return emptyList()
        val state = position.state
        val placedUser = state.pokemon.firstOrNull { it.battlePokemonId == user.battlePokemonId && it.activeSlot != null } ?: return emptyList()
        val base = pairs.pokemon(user.battlePokemonId, target.battlePokemonId) ?: return emptyList()
        // The turn spent on the move: the target attacks the user once.
        val survives = base.opponentMove?.survivalByUses?.getOrNull(1) ?: 1.0
        val taken = base.opponentMove?.damageWhileStandingByUses?.getOrNull(1) ?: 0.0
        val worn = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId != user.battlePokemonId) it
            else it.copyState(hpFraction = (it.hpFraction - taken).coerceAtLeast(MINIMUM_STANDING_HP))
        })
        val afterMiss = exchange(position, worn, user.battlePokemonId, target.battlePokemonId, cache) ?: return emptyList()
        // In doubles the target threatens the partner too, which the move helps without spending its turn.
        val partner = if (state.format != BattleFormat.DOUBLE || target.side == user.side) null else state.pokemon.firstOrNull {
            it.side == user.side && it.battlePokemonId != user.battlePokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val partnerBefore = partner?.let { exchange(position, state, it.battlePokemonId, target.battlePokemonId, cache) }
        val side = user.side
        val hypotheses = side == BattleSide.OPPONENT
        return cache.slotActions(state, side, position.publicActionCatalog, hypotheses) {
            PublicFutureActionFactory.slotActions(state, side, position.publicActionCatalog, includeMoveHypotheses = hypotheses)
        }.asSequence()
            .filter { it.kind == BattleActionKind.USE_MOVE && it.actorSlot == placedUser.activeSlot }
            .filter { it.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS }
            .mapNotNull { action ->
                val details = action.moveDetails ?: return@mapNotNull null
                val effects = details.effects?.effects.orEmpty()
                val calculated = cache.getOrCalculate(state, side, action, position.publicActionCatalog) {
                    PublicBattleTacticalCalculator.calculate(position.copy(state = state, candidates = listOf(action)), side)
                }
                val candidate = calculated.candidates.single()
                val aimsAtTarget = effects.any { it.target == BattleMoveEffectTarget.SELECTED_TARGET }
                if (aimsAtTarget && LocalPublicMoveTargets.resolve(candidate, calculated, side).firstOrNull()?.battlePokemonId != target.battlePokemonId) {
                    return@mapNotNull null
                }
                val applied = applyEffects(worn, effects, user.battlePokemonId, target,
                    LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, placedUser, target, state)) ?: return@mapNotNull null
                // A move that cannot land does no more than a miss.
                val nullified = LocalPublicMechanicsKernel.projectMove(candidate, calculated, side).publiclyNullified
                val landed = if (nullified) worn else applied
                val afterLanding = if (landed === worn) afterMiss
                    else exchange(position, landed, user.battlePokemonId, target.battlePokemonId, cache) ?: return@mapNotNull null
                val partnerGain = if (partner == null || partnerBefore == null || landed === worn) 0.0
                    else (exchange(position, landed, partner.battlePokemonId, target.battlePokemonId, cache) ?: partnerBefore) - partnerBefore
                val accuracy = (details.accuracy / 100.0).coerceIn(0.0, 1.0).takeIf { it > 0.0 } ?: 1.0
                val expected = survives * (accuracy * afterLanding + (1.0 - accuracy) * afterMiss) - (1.0 - survives) +
                    accuracy * partnerGain
                StatusMoveMatchupScore(
                    userId = user.battlePokemonId,
                    moveId = PublicIds.canonical(action.moveId ?: action.actionId),
                    targetId = target.battlePokemonId,
                    accuracy = accuracy,
                    survivesTurn = survives,
                    before = base.score,
                    afterLanding = afterLanding,
                    afterMiss = afterMiss,
                    score = (expected - base.score).coerceIn(-1.0, 1.0),
                    partnerGain = partnerGain,
                )
            }
            .distinctBy { it.moveId }
            .sortedByDescending { it.score }
            .toList()
    }

    /**
     * [incoming] from the bench taking [replaced]'s slot in front of [opponent]. The opponent picked its move
     * for [replaced]; that move lands on [incoming] after the entry hazards.
     */
    fun switchIn(
        context: BattleDecisionContext,
        incoming: BattlePokemonStateView,
        replaced: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): SwitchInScore? {
        val entered = LocalMatchupPosition.enter(context, incoming, cache, slot = replaced.activeSlot) ?: return null
        val position = LocalMatchupPosition.enter(entered, opponent, cache) ?: return null
        val predictedMoveId = pairs.pokemon(replaced.battlePokemonId, opponent.battlePokemonId)?.opponentMove?.moveId
        val againstIncoming = LocalMatchupScoreCalculator.moveMatchups(position, opponent.battlePokemonId, incoming.battlePokemonId, cache)
        val predicted = againstIncoming.firstOrNull { it.score.moveId == predictedMoveId }?.score
        val worst = againstIncoming.firstOrNull()?.score
        val survival = predicted?.survivalByUses?.getOrNull(1) ?: 1.0
        val taken = predicted?.damageWhileStandingByUses?.getOrNull(1) ?: 0.0
        val state = position.state
        val placed = state.pokemon.firstOrNull { it.battlePokemonId == incoming.battlePokemonId } ?: return null
        val hpAfterEntry = (placed.hpFraction - taken).coerceAtLeast(MINIMUM_STANDING_HP)
        val worn = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId != incoming.battlePokemonId) it else it.copyState(hpFraction = hpAfterEntry)
        })
        val afterEntry = LocalMatchupScoreCalculator.pairMatchup(position.copy(state = worn), incoming.battlePokemonId,
            opponent.battlePokemonId, MatchupSpeedField.CURRENT, cache)
        return SwitchInScore(
            incomingId = incoming.battlePokemonId,
            opponentId = opponent.battlePokemonId,
            replacedId = replaced.battlePokemonId,
            predictedMoveId = predicted?.moveId,
            predictedSurvival = survival,
            hpAfterEntry = hpAfterEntry,
            worstMoveId = worst?.moveId,
            worstSurvival = worst?.survivalByUses?.getOrNull(1) ?: 1.0,
            afterEntry = afterEntry,
            score = (survival * (afterEntry?.score ?: 0.0) - (1.0 - survival)).coerceIn(-1.0, 1.0),
        )
    }

    /** Each of [team]'s Pokemon: its team's coverage of [opponents] with and without it. */
    fun preserves(team: List<BattlePokemonStateView>, opponents: List<BattlePokemonStateView>, pairs: MatchupScores): Map<UUID, PreserveScore> {
        if (team.isEmpty() || opponents.isEmpty()) return emptyMap()
        fun win(member: BattlePokemonStateView, opponent: BattlePokemonStateView) =
            pairs.pokemon(member.battlePokemonId, opponent.battlePokemonId)?.winProbability ?: 0.0
        fun coverage(members: List<BattlePokemonStateView>) =
            opponents.map { opponent -> members.maxOfOrNull { win(it, opponent) } ?: 0.0 }.average()
        val all = coverage(team)
        return team.associate { subject ->
            val without = coverage(team - subject)
            val sole = opponents.filter { opponent ->
                win(subject, opponent) >= SOLE_ANSWER && (team - subject).none { win(it, opponent) >= SOLE_ANSWER }
            }.map { it.battlePokemonId }
            subject.battlePokemonId to PreserveScore(subject.battlePokemonId, all, without, sole, (all - without).coerceIn(0.0, 1.0))
        }
    }

    private fun exchange(
        position: BattleDecisionContext,
        state: BattleStateView,
        subjectId: UUID,
        opponentId: UUID,
        cache: LocalProjectedActionCalculationCache,
    ): Double? = LocalMatchupScoreCalculator.pairMatchup(position.copy(state = state), subjectId, opponentId,
        MatchupSpeedField.CURRENT, cache)?.score

    /** The state after [effects] land, or null when none of them is one the exchange can play out. */
    private fun applyEffects(
        state: BattleStateView,
        effects: List<BattleMoveEffectView>,
        userId: UUID,
        target: BattlePokemonStateView,
        ignoresTargetAbility: Boolean,
    ): BattleStateView? {
        var next = state
        var applied = false
        for (effect in effects) {
            if ((effect.probability ?: 1.0) < 1.0) continue
            when {
                effect.kind == BattleMoveEffectKind.STATUS && effect.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                    effect.valueId?.let(PublicIds::canonical) in playedOutStatuses() && target.statusId == null -> {
                    val status = PublicIds.canonical(effect.valueId!!)
                    val subject = next.pokemon.first { it.battlePokemonId == target.battlePokemonId }
                    val source = next.pokemon.firstOrNull { it.battlePokemonId == userId }
                    if (jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(next, subject, status, source)) continue
                    next = next.copyState(pokemon = next.pokemon.map {
                        if (it.battlePokemonId == target.battlePokemonId) it.copyState(statusId = status) else it
                    })
                    applied = true
                }
                effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.statStages.isNotEmpty() &&
                    (effect.target == BattleMoveEffectTarget.SELECTED_TARGET || effect.target == BattleMoveEffectTarget.USER) -> {
                    val subject = if (effect.target == BattleMoveEffectTarget.USER) userId else target.battlePokemonId
                    next = LocalStatStageChange.apply(next, subject, userId, effect.statStages,
                        ignoreTargetAbility = ignoresTargetAbility && effect.target == BattleMoveEffectTarget.SELECTED_TARGET)
                    applied = true
                }
            }
        }
        return jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusBerry.afterUpdate(next).takeIf { applied }
    }

    /** Statuses whose effect the exchange reads: a burn halves physical damage, paralysis halves Speed. */
    private val PLAYED_OUT_STATUSES = setOf("brn", "par")
    /** Sleep, freeze and poison too when the repeated exchange plays its turns out (Codex 9838243d, under measurement). */
    private fun playedOutStatuses(): Set<String> =
        if (jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().evolvingMatchups) EVOLVED_STATUSES else PLAYED_OUT_STATUSES
    private val EVOLVED_STATUSES = setOf("brn", "par", "slp", "frz", "psn", "tox")
    private const val MINIMUM_STANDING_HP = 0.01
    private const val SOLE_ANSWER = 0.5
}
