package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector

internal data class LocalStatStageMarginalValue(
    /** Realizable outgoing minus incoming attack-pressure change, in HP-bar units. */
    val pressureBoardDelta: Double,
    /** Public action-order probability change, in board units. */
    val speedBoardDelta: Double,
    /** Whether public moves or public Speed ranges were sufficient to price every changed stat. */
    val publiclyResolved: Boolean,
) {
    /** Position value in the same units as one HP bar. */
    val boardDelta: Double = pressureBoardDelta + speedBoardDelta
    val score: Double = boardDelta * SCORE_PER_BOARD_POINT

    private companion object {
        const val SCORE_PER_BOARD_POINT = 100.0
    }
}

/**
 * Prices stat stages by the public outcome they can actually change.
 *
 * There is deliberately no preferred raw stat such as 120 and no permanent price per stage. A
 * stage is worth the extra HP/KO pressure it creates, the incoming pressure it prevents, and the
 * public probability of crossing the action-order boundary. Damage is capped at current HP by the
 * shared lookahead pressure calculator, so an already-secured knockout gains no setup value.
 */
internal object LocalStatStageMarginalEvaluator {
    fun evaluate(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): LocalStatStageMarginalValue {
        val changes = stageChanges(before, after)
        if (changes.isEmpty()) return LocalStatStageMarginalValue(0.0, 0.0, true)

        val beforePressure = positionAttackPressure(before, source, calculationCache, tuning, shouldContinue)
        val afterPressure = positionAttackPressure(after, source, calculationCache, tuning, shouldContinue)
        val beforeSpeed = speedPositionValue(before, source, calculationCache)
        val afterSpeed = speedPositionValue(after, source, calculationCache)
        val speedDelta = orderDelta(beforeSpeed, afterSpeed)
        return LocalStatStageMarginalValue(
            pressureBoardDelta = afterPressure - beforePressure,
            speedBoardDelta = speedDelta,
            publiclyResolved = changes.all { change ->
                publicInputsResolve(change, before, source) ||
                    (change.stat == SPEED && beforeSpeed != null && afterSpeed != null)
            },
        )
    }

    /**
     * Root-action value. Exact public marginal value replaces the old flat setup score. When the
     * required move catalog or Speed range is absent, a small signed fallback keeps an unknown but
     * legal setup action alive without pretending that every stage is equally valuable.
     */
    fun candidateScore(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double? {
        val effects = candidate.moveDetails?.effects?.effects.orEmpty().filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.statStages.isNotEmpty()
        }
        if (effects.isEmpty()) return null
        // Both sides of the stage comparison use the form in which this move is actually used.
        val dynamax = jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
        var state = if (dynamax.isMechanic(candidate.mechanic?.mechanicId)) context.state.copyState(
            pokemon = context.state.pokemon.map { pokemon ->
                if (pokemon.side == BattleSide.ALLY && pokemon.activeSlot == candidate.actorSlot)
                    dynamax.start(pokemon, dynamax.isGMaxMove(candidate.mechanic?.transformedMoveId) || dynamax.gigantamax(pokemon))
                else pokemon
            }) else context.state
        var score = 0.0
        var appliedAny = false
        effects.forEach { effect ->
            val targetIds = effectTargets(candidate, state, effect.target)
            if (targetIds.isEmpty()) return@forEach
            // Contrary, Simple and drop-stopping abilities change what the move does to each target.
            val actorId = state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted
            }?.battlePokemonId
            val applied = targetIds.fold(state) { current, id ->
                val target = current.pokemon.firstOrNull { it.battlePokemonId == id } ?: return@fold current
                applyStages(current, setOf(id), LocalStatStageChange.reshape(current, target, actorId, effect.statStages))
            }
            appliedAny = true
            // An opposing target whose ability is still hidden takes the change once per ability it may have, all alike
            // (no weight for hidden abilities, user decision), as the debuff value reads it. The ability is only lent to
            // the reshaping; the board keeps it unknown.
            val worlds = abilityWorlds(state, targetIds)
            val resolvedScore = if (worlds == null) {
                scoreWithUnknownFallback(state, applied, context, calculationCache, tuning)
            } else worlds.map { lent ->
                val after = targetIds.fold(state) { current, id ->
                    val target = current.pokemon.firstOrNull { it.battlePokemonId == id } ?: return@fold current
                    val ability = lent[id] ?: return@fold applyStages(current, setOf(id),
                        LocalStatStageChange.reshape(current, target, actorId, effect.statStages))
                    val borrowed = current.copyState(pokemon = current.pokemon.map {
                        if (it.battlePokemonId == id) it.copyState(knownAbilityId = ability) else it
                    })
                    val holder = borrowed.pokemon.first { it.battlePokemonId == id }
                    applyStages(current, setOf(id), LocalStatStageChange.reshape(borrowed, holder, actorId, effect.statStages))
                }
                scoreWithUnknownFallback(state, after, context, calculationCache, tuning)
            }.average()
            val probability = (effect.probability ?: 1.0).coerceIn(0.0, 1.0)
            score += resolvedScore * probability
            if (probability >= CERTAIN_PROBABILITY) state = applied
        }
        // Valued on the board before the hit, as the move is chosen there (user, 2026-10-21): a knockout and a stage change
        // are two values of the same move, the hit's HP taken (capped at what the target has) and then the stages. Only a
        // move that does no damage (an immunity) gives no stages: its effects never happen, and a Max Strike into an immune
        // Gengar took 200 for a Speed drop (self-play, 2026-10-21).
        val damaging = candidate.moveDetails?.damageCategory.let { it != null && it != BattleMoveDamageCategory.STATUS }
        val lands = if (damaging && (candidate.facts?.standardDamageFractionRange?.maximum ?: 1.0) <= 0.0) 0.0 else 1.0
        if (!appliedAny) return null
        val value = score * accuracy.coerceIn(0.0, 1.0) * lands
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("statStages:${candidate.actionId}", mapOf(
            "raw" to score, "accuracy" to accuracy.coerceIn(0.0, 1.0), "lands" to lands, "value" to value))
        return value
    }

    /**
     * Every combination of the abilities the opposing [targetIds] with a hidden ability may have, or null when none is
     * in doubt (every target's ability is known, or it can only have one).
     */
    private fun abilityWorlds(state: BattleStateView, targetIds: Collection<UUID>): List<Map<UUID, String>>? {
        val doubtful = targetIds.mapNotNull { id ->
            val target = state.pokemon.firstOrNull { it.battlePokemonId == id && it.side == BattleSide.OPPONENT && it.knownAbilityId == null }
                ?: return@mapNotNull null
            LocalEntryAbilityProjector.publicPossibleAbilities(state, target).takeIf { it.size >= 2 }?.let { id to it }
        }
        if (doubtful.isEmpty()) return null
        return doubtful.fold(listOf(emptyMap())) { worlds, (id, abilities) ->
            worlds.flatMap { world -> abilities.map { world + (id to it) } }
        }
    }

    /**
     * Keeps exact outcome value for every changed stat whose public inputs are available, and uses
     * the small signed reserve only for the remainder. Treating a mixed effect as all-known or
     * all-unknown made one unrevealed axis erase useful public damage information for every other
     * axis on the same move.
     */
    private fun scoreWithUnknownFallback(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
    ): Double {
        val changes = stageChanges(before, after)
        if (changes.isEmpty()) return 0.0
        val speedResolved = speedPositionValue(before, source, calculationCache) != null &&
            speedPositionValue(after, source, calculationCache) != null
        val (resolved, unresolved) = changes.partition { change ->
            publicInputsResolve(change, before, source) || change.stat == SPEED && speedResolved
        }
        if (unresolved.isEmpty()) {
            return evaluate(before, after, source, calculationCache, tuning).score
        }
        val resolvedScore = if (resolved.isEmpty()) {
            0.0
        } else {
            evaluate(
                before,
                applyStageChanges(before, resolved),
                source,
                calculationCache,
                tuning,
            ).score
        }
        // An opposing attacking stat whose attacks are all still hidden is priced on the attacks it is expected to carry
        // (the move hypotheses: its STAB and coverage attacks, never a guessed status move). The flat reserve read the
        // Attack +2 a Defiant Annihilape takes from Icy Wind as 8 points, against 30 for the Speed drop (2026-10-12).
        fun hypothesisPressure(state: BattleStateView) = LocalLookaheadStateEvaluator.attackPressure(
            state, BattleSide.OPPONENT, source, calculationCache, tuning = tuning,
            capDamageToRemainingHp = true, includeMoveHypotheses = true,
        )
        val hypothesised = unresolved.filter { it.side == BattleSide.OPPONENT && (it.stat == ATTACK || it.stat == SPECIAL_ATTACK) }
            .takeIf { it.isNotEmpty() && hypothesisPressure(before) > 0.0 }.orEmpty()
        val hypothesisScore = if (hypothesised.isEmpty()) 0.0
            else -(hypothesisPressure(applyStageChanges(before, hypothesised)) - hypothesisPressure(before)) * SCORE_PER_BOARD_POINT
        val fallback = fallbackScore(before, applyStageChanges(before, unresolved - hypothesised.toSet())) + hypothesisScore
        // An unknown opposing physical set does not make our own Body Press unknowable.
        // Preserve that calculable offensive part while the fallback covers only the
        // unresolved defensive part of the same Defense stage change.
        val bodyPressScore = unresolved.filter { it.stat == DEFENSE }.sumOf { change ->
            val attacker = before.pokemon.firstOrNull { it.battlePokemonId == change.pokemonId }
            val hasBodyPress = source.publicActionCatalog.forPokemon(change.pokemonId).any {
                canonical(it.moveId) == "bodypress"
            }
            if (!hasBodyPress || !attacker.hasPublicCombatStats() ||
                active(before, opposite(change.side)).none { it.hasPublicCombatStats() }
            ) {
                0.0
            } else {
                val changed = applyStageChanges(before, listOf(change))
                val beforePressure = LocalLookaheadStateEvaluator.attackPressure(
                    before, change.side, source, calculationCache, tuning = tuning,
                    capDamageToRemainingHp = true,
                )
                val afterPressure = LocalLookaheadStateEvaluator.attackPressure(
                    changed, change.side, source, calculationCache, tuning = tuning,
                    capDamageToRemainingHp = true,
                )
                (afterPressure - beforePressure) *
                    (if (change.side == BattleSide.ALLY) 1.0 else -1.0) * SCORE_PER_BOARD_POINT
            }
        }
        return resolvedScore + fallback + bodyPressScore
    }

    /** Values only rank changes while preserving the actual post-turn HP, field and active slots. */
    fun transitionValue(
        beforeTurn: BattleStateView,
        afterTurn: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): LocalStatStageMarginalValue {
        // Most projected turns change no stage; they need neither the copied board nor the pressure reads.
        if (!stagesChanged(beforeTurn, afterTurn)) return LocalStatStageMarginalValue(0.0, 0.0, true)
        val beforeById = beforeTurn.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        val postTurnWithoutStageChanges = afterTurn.copyState(
            pokemon = afterTurn.pokemon.map { pokemon ->
                val oldStages = beforeById[pokemon.battlePokemonId]?.statStages ?: pokemon.statStages
                pokemon.copyState(statStages = oldStages)
            },
        )
        return evaluate(
            postTurnWithoutStageChanges,
            afterTurn,
            source,
            calculationCache,
            tuning,
            shouldContinue,
        )
    }

    fun speedTransitionBoardDelta(
        before: BattleStateView,
        after: BattleStateView,
        @Suppress("UNUSED_PARAMETER") tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        /** The board the hits are read on; without it the order is worth nothing it can be shown to change. */
        source: BattleDecisionContext? = null,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): Double {
        source ?: return 0.0
        return orderDelta(speedPositionValue(before, source, calculationCache), speedPositionValue(after, source, calculationCache))
    }

    private fun positionAttackPressure(
        state: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
        shouldContinue: () -> Boolean,
    ): Double = LocalLookaheadStateEvaluator.attackPressure(
        state,
        BattleSide.ALLY,
        source,
        calculationCache,
        shouldContinue,
        tuning,
        capDamageToRemainingHp = true,
    ) - LocalLookaheadStateEvaluator.attackPressure(
        state,
        BattleSide.OPPONENT,
        source,
        calculationCache,
        shouldContinue,
        tuning,
        capDamageToRemainingHp = true,
    )

    /** One pair's order: the chance the ally moves first, and what the order decides between them (in HP bars). */
    private class Order(val first: Double, val stakes: Double)

    /**
     * What a change of order is worth, in HP bars, over each pair of active Pokemon (their average): the change in the
     * chance of moving first times what the order decides on the board before it,
     * ```
     * (first after - first before) x (our knockout x their hit + their knockout x our hit)
     * ```
     * the hit moving first stops and the hit moving second loses, each its expected damage (the middle of its roll, its
     * accuracy) up to the HP it can take (user, 2026-10-21). An order that decides no knockout is worth nothing, and a
     * change that leaves the order as it was is worth nothing whatever else it changes (a Defence drop's larger hits are
     * the pressure's). It used to be a flat (2 x first - 1) x 0.25 of a bar, half a bar for any change of order: a
     * Torkoal's Rapid Spin took 50 for its Speed raise and outranked a super-effective Lava Plume that did three times the
     * damage, neither side knocking the other out (self-play, 2026-10-21).
     */
    private fun orderDelta(before: Map<Pair<UUID, UUID>, Order>?, after: Map<Pair<UUID, UUID>, Order>?): Double {
        if (before == null || after == null || before.isEmpty()) return 0.0
        return before.entries.map { (pair, order) ->
            ((after[pair]?.first ?: order.first) - order.first) * order.stakes
        }.average()
    }

    /** Each active pair's [Order] on [state]; null when the order cannot be read from public Speed. */
    private fun speedPositionValue(
        state: BattleStateView,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
    ): Map<Pair<UUID, UUID>, Order>? {
        val allies = active(state, BattleSide.ALLY)
        val opponents = active(state, BattleSide.OPPONENT)
        if (allies.isEmpty() || opponents.isEmpty()) return null
        val board = source.copy(state = state)
        fun hit(move: jbro.cobblemon.mcc.betterai.matchup.MoveMatchupScore?, hp: Double) =
            move?.let { minOf(hp, (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy) } ?: 0.0
        return allies.flatMap { ally ->
            opponents.map { opponent ->
                LocalPublicTurnOrder.speedOrderProbability(state, ally, opponent) ?: return null
                val key = ally.battlePokemonId to opponent.battlePokemonId
                val pair = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.pairMatchup(board, ally.battlePokemonId,
                    opponent.battlePokemonId, jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField.CURRENT, cache)
                    ?: return@map key to Order(0.5, 0.0)
                key to Order(pair.subjectMovesFirstProbability,
                    (pair.subjectMove?.knockoutChanceWithin(1) ?: 0.0) * hit(pair.opponentMove, ally.hpFraction) +
                        (pair.opponentMove?.knockoutChanceWithin(1) ?: 0.0) * hit(pair.subjectMove, opponent.hpFraction))
            }
        }.toMap()
    }

    private fun publicInputsResolve(
        change: StageChange,
        state: BattleStateView,
        source: BattleDecisionContext,
    ): Boolean {
        val ownCatalog = source.publicActionCatalog.forPokemon(change.pokemonId)
        val damagingOwnMoves = ownCatalog.filter { it.details.damageCategory != BattleMoveDamageCategory.STATUS }
        return when (change.stat) {
            ATTACK -> outgoingMovesResolve(
                change,
                state,
                source,
                damagingOwnMoves.any { it.details.damageCategory == BattleMoveDamageCategory.PHYSICAL },
            )
            SPECIAL_ATTACK -> outgoingMovesResolve(
                change,
                state,
                source,
                damagingOwnMoves.any { it.details.damageCategory == BattleMoveDamageCategory.SPECIAL },
            )
            ACCURACY -> outgoingMovesResolve(change, state, source, damagingOwnMoves.isNotEmpty())
            DEFENSE -> incomingMovesResolve(change, BattleMoveDamageCategory.PHYSICAL, state, source)
            SPECIAL_DEFENSE -> incomingMovesResolve(change, BattleMoveDamageCategory.SPECIAL, state, source)
            EVASION -> incomingMovesResolve(change, null, state, source)
            SPEED -> false
            else -> false
        }
    }

    private fun outgoingMovesResolve(
        change: StageChange,
        state: BattleStateView,
        source: BattleDecisionContext,
        relevantMoveExists: Boolean,
    ): Boolean {
        if (!relevantMoveExists) return source.publicActionCatalog.isMoveSetComplete(change.pokemonId)
        val attacker = state.pokemon.firstOrNull { it.battlePokemonId == change.pokemonId }
        val defenders = active(state, opposite(change.side))
        return attacker.hasPublicCombatStats() && defenders.any { it.hasPublicCombatStats() }
    }

    private fun incomingMovesResolve(
        change: StageChange,
        category: BattleMoveDamageCategory?,
        state: BattleStateView,
        source: BattleDecisionContext,
    ): Boolean {
        val attackers = active(state, opposite(change.side))
        if (attackers.isEmpty()) return true
        val targetHasStats = state.pokemon.firstOrNull {
            it.battlePokemonId == change.pokemonId
        }.hasPublicCombatStats()
        if (!targetHasStats) return false
        return attackers.all { attacker ->
            val relevantMoveExists = source.publicActionCatalog.forPokemon(attacker.battlePokemonId).any { option ->
                option.details.damageCategory != BattleMoveDamageCategory.STATUS &&
                    (category == null || option.details.damageCategory == category)
            }
            if (relevantMoveExists) {
                attacker.hasPublicCombatStats()
            } else {
                source.publicActionCatalog.isMoveSetComplete(attacker.battlePokemonId)
            }
        }
    }

    private fun BattlePokemonStateView?.hasPublicCombatStats(): Boolean =
        this?.level != null && this.combatStats != null

    private fun fallbackScore(before: BattleStateView, after: BattleStateView): Double {
        val trickRoom = before.field.roomEffects.any {
            val remainingTurns = it.remainingTurns
            canonical(it.effectId) == "trickroom" && (remainingTurns == null || remainingTurns > 0)
        }
        return stageChanges(before, after).sumOf { change ->
            val perspective = if (change.side == BattleSide.ALLY) 1.0 else -1.0
            val fieldDirection = if (change.stat == SPEED && trickRoom) -1.0 else 1.0
            change.delta * perspective * fieldDirection * FALLBACK_SCORE_PER_STAGE
        }
    }

    private fun effectTargets(
        candidate: BattleActionCandidate,
        state: BattleStateView,
        target: BattleMoveEffectTarget,
    ): Set<UUID> = when (target) {
        BattleMoveEffectTarget.USER -> candidate.actorSlot?.let { slot ->
            active(state, BattleSide.ALLY).singleOrNull { it.activeSlot == slot }?.battlePokemonId
        }?.let(::setOf).orEmpty()
        BattleMoveEffectTarget.SELECTED_TARGET -> candidate.targets.mapNotNullTo(linkedSetOf()) { slot ->
            state.pokemon.singleOrNull {
                it.side == slot.side && it.activeSlot == slot.slot && !it.fainted && it.hpFraction > 0.0
            }?.battlePokemonId
        }
        BattleMoveEffectTarget.USER_SIDE,
        BattleMoveEffectTarget.TARGET_SIDE,
        BattleMoveEffectTarget.FIELD,
        -> emptySet()
    }

    /** [changes] added to the stages of [targetIds], stat names canonicalized and clamped to +-6. */
    /**
     * What the stat changes [effects] make to [user] itself are worth to this side, in board units: positive when they
     * help us, as an opposing Close Combat lowering its own defences or Draco Meteor its Special Attack does. Read the
     * way our own moves' changes are ([candidateScore]): by the pressure they change in the position in front of it, an
     * opposing attacking stat whose attacks are hidden by the attacks it is expected to carry. For the opponent-intent
     * prediction, which priced an attack by the damage it does alone (2026-10-18).
     */
    fun selfChangeBoard(
        user: BattlePokemonStateView,
        effects: List<BattleMoveEffectView>,
        context: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        val own = effects.filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER && it.statStages.isNotEmpty()
        }
        if (own.isEmpty()) return 0.0
        var state = context.state
        var score = 0.0
        own.forEach { effect ->
            val holder = state.pokemon.firstOrNull { it.battlePokemonId == user.battlePokemonId } ?: return@forEach
            val applied = applyStages(state, setOf(user.battlePokemonId),
                LocalStatStageChange.reshape(state, holder, user.battlePokemonId, effect.statStages))
            val probability = (effect.probability ?: 1.0).coerceIn(0.0, 1.0)
            score += scoreWithUnknownFallback(state, applied, context, calculationCache, tuning) * probability
            if (probability >= CERTAIN_PROBABILITY) state = applied
        }
        return score / SCORE_PER_BOARD_POINT
    }

    internal fun applyStages(
        state: BattleStateView,
        targetIds: Set<UUID>,
        changes: Map<String, Int>,
    ): BattleStateView = state.copyState(
        pokemon = state.pokemon.map { pokemon ->
            if (pokemon.battlePokemonId !in targetIds || pokemon.fainted || pokemon.hpFraction <= 0.0) {
                pokemon
            } else {
                val stages = canonicalStages(pokemon.statStages).toMutableMap()
                changes.forEach { (stat, amount) ->
                    val id = canonical(stat)
                    stages[id] = ((stages[id] ?: 0) + amount).coerceIn(-6, 6)
                }
                pokemon.copyState(statStages = stages)
            }
        },
    )

    private fun applyStageChanges(
        state: BattleStateView,
        changes: List<StageChange>,
    ): BattleStateView =
        changes.groupBy(StageChange::pokemonId).entries.fold(state) { projected, (pokemonId, pokemonChanges) ->
            applyStages(
                projected,
                setOf(pokemonId),
                pokemonChanges.associate { change -> change.stat to change.delta },
            )
        }

    /** Whether any Pokemon's stages differ, without building the change list; views share an unchanged map. */
    private fun stagesChanged(before: BattleStateView, after: BattleStateView): Boolean {
        if (before.pokemon.size == after.pokemon.size) {
            var aligned = true
            for (index in before.pokemon.indices) {
                val old = before.pokemon[index]
                val new = after.pokemon[index]
                if (old.battlePokemonId != new.battlePokemonId) { aligned = false; break }
                if (old.statStages !== new.statStages && old.statStages != new.statStages) return true
            }
            if (aligned) return false
        }
        return stageChanges(before, after).isNotEmpty()
    }

    private fun stageChanges(before: BattleStateView, after: BattleStateView): List<StageChange> {
        val beforeById = before.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        return after.pokemon.flatMap { pokemon ->
            val previous = beforeById[pokemon.battlePokemonId] ?: return@flatMap emptyList()
            val oldStages = canonicalStages(previous.statStages)
            val newStages = canonicalStages(pokemon.statStages)
            (oldStages.keys + newStages.keys).mapNotNull { stat ->
                val delta = (newStages[stat] ?: 0) - (oldStages[stat] ?: 0)
                if (delta == 0) null else StageChange(pokemon.battlePokemonId, pokemon.side, stat, delta)
            }
        }
    }

    private fun canonicalStages(stages: Map<String, Int>): Map<String, Int> = stages.entries.associate { (stat, value) ->
        canonical(stat) to value.coerceIn(-6, 6)
    }

    private fun active(state: BattleStateView, side: BattleSide): List<BattlePokemonStateView> = state.pokemon.filter {
        it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
    }

    private fun opposite(side: BattleSide): BattleSide =
        if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY

    private fun canonical(value: String?): String = PublicIds.canonical(value.orEmpty())
        .let { STAT_ALIASES[it] ?: it }

    private data class StageChange(
        val pokemonId: UUID,
        val side: BattleSide,
        val stat: String,
        val delta: Int,
    )

    private const val CERTAIN_PROBABILITY = 0.999_999
    private const val FALLBACK_SCORE_PER_STAGE = 4.0
    private const val SCORE_PER_BOARD_POINT = 100.0
    private const val ATTACK = "attack"
    private const val DEFENSE = "defense"
    private const val SPECIAL_ATTACK = "specialattack"
    private const val SPECIAL_DEFENSE = "specialdefense"
    private const val SPEED = "speed"
    private const val ACCURACY = "accuracy"
    private const val EVASION = "evasion"
    private val STAT_ALIASES = mapOf(
        "atk" to ATTACK,
        "def" to DEFENSE,
        "defence" to DEFENSE,
        "spa" to SPECIAL_ATTACK,
        "spatk" to SPECIAL_ATTACK,
        "specialatk" to SPECIAL_ATTACK,
        "spd" to SPECIAL_DEFENSE,
        "spdef" to SPECIAL_DEFENSE,
        "specialdefence" to SPECIAL_DEFENSE,
        "spe" to SPEED,
        "acc" to ACCURACY,
        "eva" to EVASION,
    )
}
