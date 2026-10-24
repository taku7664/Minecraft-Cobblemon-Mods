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
        // In doubles the target threatens the partner too, which the move helps without spending its turn.
        val partner = if (state.format != BattleFormat.DOUBLE || target.side == user.side) null else state.pokemon.firstOrNull {
            it.side == user.side && it.battlePokemonId != user.battlePokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val simple = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().simpleStatusMatchups
        fun continuation(next: BattleStateView, subject: java.util.UUID): Double? {
            return if (simple) pairs.pokemon(subject, target.battlePokemonId)?.let {
                LocalSimpleMatchup.exchange(position, position.copy(state = next), it)
            } else LocalHpMatchupAudit.statusExchange {
                exchange(position, next, subject, target.battlePokemonId, cache)
            }
        }
        var prepared = false
        var before = base.score
        var afterMiss: Double? = null
        var partnerBefore: Double? = null
        fun prepareExchanges(): Boolean {
            if (!prepared) {
                prepared = true
                LocalHpMatchupAudit.statusPrepared()
                if (simple) before = LocalSimpleMatchup.exchange(position, position, base)
                afterMiss = continuation(worn, user.battlePokemonId)
                if (afterMiss != null) partnerBefore = partner?.let {
                    continuation(state, it.battlePokemonId)
                }
            }
            return afterMiss != null
        }
        LocalHpMatchupAudit.statusVisit()
        // Defer only the unused baseline exchanges. The effect eligibility and scoring stay shared.
        if (!jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().skipUnusedStatusMatchups && !prepareExchanges()) {
            return emptyList()
        }
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
                    LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, placedUser, target, state),
                    LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, placedUser, null, state)) ?: return@mapNotNull null
                if (!prepareExchanges()) return@mapNotNull null
                val missed = checkNotNull(afterMiss)
                val partnerBase = partnerBefore
                // A move that cannot land does no more than a miss.
                val nullified = LocalPublicMechanicsKernel.projectMove(candidate, calculated, side).publiclyNullified
                val landed = if (nullified) worn else applied
                val afterLanding = if (landed === worn) missed
                    else continuation(landed, user.battlePokemonId) ?: return@mapNotNull null
                val partnerGain = if (partner == null || partnerBase == null || landed === worn) 0.0
                    else (continuation(landed, partner.battlePokemonId) ?: partnerBase) - partnerBase
                val accuracy = (details.accuracy / 100.0).coerceIn(0.0, 1.0).takeIf { it > 0.0 } ?: 1.0
                val expected = survives * (accuracy * afterLanding + (1.0 - accuracy) * missed) - (1.0 - survives) +
                    accuracy * partnerGain
                if (simple) jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record(
                    "simpleStatusMove:${user.battlePokemonId}:${target.battlePokemonId}:${action.moveId}", mapOf(
                        "survives" to survives, "accuracy" to accuracy, "landed" to afterLanding, "missed" to missed,
                        "partnerGain" to partnerGain, "before" to before, "expected" to expected,
                        "score" to (expected - before).coerceIn(-1.0, 1.0)))
                StatusMoveMatchupScore(
                    userId = user.battlePokemonId,
                    moveId = PublicIds.canonical(action.moveId ?: action.actionId),
                    targetId = target.battlePokemonId,
                    accuracy = accuracy,
                    survivesTurn = survives,
                    before = before,
                    afterLanding = afterLanding,
                    afterMiss = missed,
                    score = (expected - before).coerceIn(-1.0, 1.0),
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
        intent: OpponentIntent?,
    ): SwitchInScore? {
        val entered = LocalMatchupPosition.enter(context, incoming, cache, slot = replaced.activeSlot) ?: return null
        val placedIncoming = entered.state.pokemon.firstOrNull { it.battlePokemonId == incoming.battlePokemonId } ?: return null
        // Entry hazards happen before either side's move. No incoming attack to score does not mean this Pokemon lives.
        if (placedIncoming.fainted || placedIncoming.hpFraction <= 0.0) return SwitchInScore(
            incoming.battlePokemonId, opponent.battlePokemonId, replaced.battlePokemonId,
            predictedMoveId = null, predictedSurvival = 0.0, hpAfterEntry = 0.0,
            worstMoveId = null, worstSurvival = 0.0, afterEntry = null, score = -1.0, entryFaints = true,
        )
        val position = LocalMatchupPosition.enter(entered, opponent, cache) ?: return null
        val againstIncoming = LocalMatchupScoreCalculator.moveMatchups(position, opponent.battlePokemonId, incoming.battlePokemonId, cache)
        val worst = againstIncoming.firstOrNull()?.score
        val state = position.state
        val placed = state.pokemon.firstOrNull { it.battlePokemonId == incoming.battlePokemonId } ?: return null
        val actor = state.pokemon.first { it.battlePokemonId == opponent.battlePokemonId }
        // The ordinary attack table drops moves that fail on this new target. They can still be
        // exactly what the opponent chose against the outgoing Pokemon, including absorbed status moves.
        val absorptionActions by lazy {
            PublicFutureActionFactory.primitiveActionsForPokemon(state, actor.side, actor.battlePokemonId,
                position.publicActionCatalog, includeMoveHypotheses = actor.side == BattleSide.OPPONENT)
                .filter { it.kind == BattleActionKind.USE_MOVE }
                .map { jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.asMaxMove(it, actor) }
                .filter { action -> LocalPublicMoveTargets.resolve(action, position, actor.side).any {
                    it.battlePokemonId == incoming.battlePokemonId } }
                .associateBy { PublicIds.canonical(it.moveId.orEmpty()) }
        }
        // All move outcomes are calculated once. A later repeat/tendency reading changes only their weights.
        val exchanges = HashMap<Triple<Double, Map<String, Int>, Set<String>>, PokemonMatchupScore?>()
        val reactedExchanges = HashMap<String, PokemonMatchupScore?>()
        fun reactionEntry(moveId: String, action: jbro.cobblemon.mcc.internal.ai.BattleActionCandidate): SwitchInOutcome? {
            val impact = jbro.cobblemon.mcc.betterai.mechanics.LocalMoveImpactProjector
            val paths = impact.project(action, position, actor.side, cache)
            // A declared hook that never fires does not replace the existing ordinary hit formula.
            // Truant still advances the attacker's phase in both control and actual states.
            if (!jbro.cobblemon.mcc.betterai.mechanics.LocalTruant.active(state, actor) && paths.all {
                    cache.fingerprints.of(it.state) == cache.fingerprints.of(it.bodyState) }) return null
            var representative: PokemonMatchupScore? = null
            val branches = paths.map { path ->
                val arrived = path.state.pokemon.first { it.battlePokemonId == incoming.battlePokemonId }
                val body = path.bodyState.pokemon.first { it.battlePokemonId == incoming.battlePokemonId }
                val survives = !arrived.fainted && arrived.hpFraction > 0.0
                val after = if (!survives) null else reactedExchanges.getOrPut(cache.fingerprints.of(path.state)) {
                    LocalMatchupScoreCalculator.pairMatchup(position.copy(state = path.state), incoming.battlePokemonId,
                        opponent.battlePokemonId, MatchupSpeedField.CURRENT, cache)
                }
                if (representative == null) representative = after
                SwitchInReactionBranch(path.probability, survives, arrived.hpFraction, after?.score ?: 0.0,
                    if (survives) after?.score ?: 0.0 else -1.0,
                    (arrived.hpFraction - body.hpFraction).coerceAtLeast(0.0), arrived.statStages,
                    arrived.knownHeldItemId != placed.knownHeldItemId)
            }
            val survival = branches.filter { it.survives }.sumOf { it.probability }
            val hp = if (survival > 0.0) branches.filter { it.survives }.sumOf { it.probability * it.hp } / survival else 0.0
            val continuation = if (survival > 0.0) branches.filter { it.survives }.sumOf { it.probability * it.continuation } / survival else 0.0
            val absorbs = !jbro.cobblemon.mcc.betterai.mechanics.LocalTruant.loafs(state, actor) &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalMoveAbsorption.apply(state, action, actor, placed) != null
            return SwitchInOutcome(moveId, survival, hp, representative, branches.sumOf { it.probability * it.score },
                absorptionHealing = if (absorbs) (hp - placed.hpFraction).coerceAtLeast(0.0) else 0.0,
                absorptionStages = if (absorbs) branches.firstOrNull()?.stages.orEmpty().filter { (stat, value) ->
                    value != (placed.statStages[stat] ?: 0) }.mapValues { (stat, value) -> value - (placed.statStages[stat] ?: 0) } else emptyMap(),
                absorptionActivated = absorbs, continuationScore = continuation, reactionBranches = branches)
        }
        fun entry(moveId: String?, scored: LocalMatchupScoreCalculator.ScoredMove?): SwitchInOutcome {
            val action = scored?.action ?: moveId?.let { absorptionActions[it] }
            if (moveId != null && action != null &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalMoveImpactProjector.mayReact(action, position, actor.side))
                reactionEntry(moveId, action)?.let { return it }
            val absorbed = action?.let {
                jbro.cobblemon.mcc.betterai.mechanics.LocalMoveAbsorption.apply(state, it, actor, placed)
            }
            val hit = scored?.score.takeIf { absorbed == null }
            val survival = hit?.survivalByUses?.getOrNull(1) ?: 1.0
            val taken = hit?.damageWhileStandingByUses?.getOrNull(1) ?: 0.0
            val reacted = absorbed ?: state
            val hpAfterEntry = if (absorbed != null) absorbed.pokemon.first { it.battlePokemonId == incoming.battlePokemonId }.hpFraction
                else (placed.hpFraction - taken).coerceAtLeast(MINIMUM_STANDING_HP)
            val worn = reacted.copyState(pokemon = reacted.pokemon.map {
                if (it.battlePokemonId != incoming.battlePokemonId) it else it.copyState(hpFraction = hpAfterEntry)
            })
            val arrived = worn.pokemon.first { it.battlePokemonId == incoming.battlePokemonId }
            val afterEntry = exchanges.getOrPut(Triple(hpAfterEntry, arrived.statStages, arrived.knownVolatileEffectIds)) {
                LocalMatchupScoreCalculator.pairMatchup(position.copy(state = worn), incoming.battlePokemonId,
                    opponent.battlePokemonId, MatchupSpeedField.CURRENT, cache)
            }
            return SwitchInOutcome(moveId, survival, hpAfterEntry, afterEntry,
                survival * (afterEntry?.score ?: 0.0) - (1.0 - survival),
                absorptionHealing = (hpAfterEntry - placed.hpFraction).coerceAtLeast(0.0),
                absorptionStages = arrived.statStages.filter { (stat, value) -> value != (placed.statStages[stat] ?: 0) }
                    .mapValues { (stat, value) -> value - (placed.statStages[stat] ?: 0) },
                absorptionActivated = absorbed != null)
        }
        val hits = againstIncoming.associateBy { PublicIds.canonical(it.score.moveId) }
        val moveIds = (pairs.moves(opponent.battlePokemonId, replaced.battlePokemonId).map { it.moveId } +
            intent?.options.orEmpty().filter { it.kind != IntentKind.SWITCH }.mapNotNull { it.moveId })
            .map(PublicIds::canonical).distinct()
        val outcomes = linkedMapOf<String?, SwitchInOutcome>(null to entry(null, null))
        for (id in moveIds) outcomes[id] = entry(id, hits[id])
        val result = SwitchInScore(
            incomingId = incoming.battlePokemonId,
            opponentId = opponent.battlePokemonId,
            replacedId = replaced.battlePokemonId,
            predictedMoveId = null,
            predictedSurvival = 1.0,
            hpAfterEntry = placed.hpFraction,
            worstMoveId = worst?.moveId,
            worstSurvival = worst?.survivalByUses?.getOrNull(1) ?: 1.0,
            afterEntry = outcomes.getValue(null).afterEntry,
            score = outcomes.getValue(null).score,
            outcomes = outcomes,
        )
        return intent?.let(result::withIntent) ?: result
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
        /** The move ignores abilities at all, whoever's shield (a partner's veil). */
        abilityIgnoringMove: Boolean = ignoresTargetAbility,
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
                        ignoreTargetAbility = ignoresTargetAbility && effect.target == BattleMoveEffectTarget.SELECTED_TARGET,
                        abilityIgnoringMove = abilityIgnoringMove && effect.target == BattleMoveEffectTarget.SELECTED_TARGET)
                    applied = true
                }
            }
        }
        return jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusBerry.afterUpdate(next).takeIf { applied }
    }

    /** Statuses whose effect the exchange reads: a burn halves physical damage, paralysis halves Speed. */
    private val PLAYED_OUT_STATUSES = setOf("brn", "par")
    /**
     * The statuses the exchange reads: a burn and paralysis through the damage and Speed they change, and sleep, freeze
     * and poison too when the repeated exchange plays its turns out (Codex 9838243d, under measurement).
     */
    internal fun playedOutStatuses(): Set<String> =
        if (jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().evolvingMatchups) EVOLVED_STATUSES else PLAYED_OUT_STATUSES
    private val EVOLVED_STATUSES = setOf("brn", "par", "slp", "frz", "psn", "tox")
    private const val MINIMUM_STANDING_HP = 0.01
    /** Score gap under which two of the opponent's moves count as the same choice against the Pokemon in front. */
    private const val SOLE_ANSWER = 0.5
}
