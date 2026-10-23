package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalItemTransitionEvaluator.Score
import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope

/** Immediate hit reactions belong to the move that causes them. Damage and declared stages
 * stay with their existing owners; reaction stages are valued on the pre-hit board.
 */
internal object LocalRootHitReactionEvaluator {
    private data class OptionalScore(val value: Score?)
    private fun actionKey(action: BattleActionCandidate): List<Any?> = listOf(action.actionId, action.kind,
        action.actorSlot, action.moveSlot, action.moveId, action.targets, action.mechanic, action.moveDetails,
        action.tags, action.componentActions.map(::actionKey))

    fun score(candidate: BattleActionCandidate, context: BattleDecisionContext,
        tuning: LocalDecisionTuning): Score? = LocalDecisionCalculationScope.reuse("rootHitReaction",
        LocalDecisionCalculationScope.Identity(context), tuning, actionKey(candidate)) {
        OptionalScore(calculate(candidate, context, tuning))
    }.value

    private fun calculate(candidate: BattleActionCandidate, context: BattleDecisionContext,
        tuning: LocalDecisionTuning): Score? {
        if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS ||
            !LocalMoveImpactProjector.mayReact(candidate, context, BattleSide.ALLY)) return null
        if (tuning.simpleFixedHpReactions) LocalFixedHpReactionValue.read(candidate, context, tuning)?.let { return it }
        val cache = LocalProjectedActionCalculationCache()
        val paths = LocalMoveImpactProjector.project(candidate, context, BattleSide.ALLY, cache)
        if (paths.all { cache.fingerprints.of(it.state) == cache.fingerprints.of(it.bodyState) }) return null
        return price(paths, candidate.actionId, context, cache, tuning)
    }

    /** The joint action owns one sequential reaction state, including a berry consumed by its first move. */
    fun compositeCorrection(candidate: BattleActionCandidate, context: BattleDecisionContext,
        tuning: LocalDecisionTuning): Score = LocalDecisionCalculationScope.reuse("jointHitReaction",
        LocalDecisionCalculationScope.Identity(context), tuning, actionKey(candidate)) {
        calculateComposite(candidate, context, tuning)
    }

    private fun calculateComposite(candidate: BattleActionCandidate, context: BattleDecisionContext,
        tuning: LocalDecisionTuning): Score {
        val actions = candidate.componentActions
        if (actions.size != 2 || actions.any { it.kind != BattleActionKind.USE_MOVE ||
                it.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS }) return Score()
        val targets = actions.map { LocalPublicMoveTargets.resolve(it, context, BattleSide.ALLY).map { mon -> mon.battlePokemonId }.toSet() }
        if (targets[0].intersect(targets[1]).isEmpty() || actions.none {
                LocalMoveImpactProjector.mayReact(it, context, BattleSide.ALLY, includeGuards = false) }) return Score()
        val cache = LocalProjectedActionCalculationCache()
        val first = LocalPublicTurnOrder.actsFirstProbability(context.state, BattleSide.ALLY, actions[0], BattleSide.ALLY, actions[1]) ?: 0.5
        val paths = listOf(actions to first, actions.reversed() to 1.0 - first).filter { it.second > 0.0 }.flatMap { (order, chance) ->
            var branches = listOf(LocalMoveImpactProjector.Path(context.state, context.state, chance))
            for (action in order) {
                branches = branches.flatMap { parent ->
                    LocalMoveImpactProjector.project(action, context.copy(state = parent.state, candidates = listOf(action)),
                        BattleSide.ALLY, cache, parent.bodyState).map { it.copy(probability = it.probability * parent.probability) }
                }.groupBy { cache.fingerprints.of(it.state) to cache.fingerprints.of(it.bodyState) }.values.map { same ->
                    same.first().copy(probability = same.sumOf { it.probability }) }
            }
            branches
        }
        val joint = if (paths.all { cache.fingerprints.of(it.state) == cache.fingerprints.of(it.bodyState) }) Score()
            else price(paths, candidate.actionId, context, cache, tuning)
        val independent = actions.map { score(it, context, tuning) ?: Score() }
        return Score(joint.total - independent.sumOf { it.total },
            joint.statStageUtility - independent.sumOf { it.statStageUtility }, joint.itemUtility - independent.sumOf { it.itemUtility })
    }

    private fun price(paths: List<LocalMoveImpactProjector.Path>, actionId: String, context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache, tuning: LocalDecisionTuning): Score {
        var hp = 0.0
        var knockout = 0.0
        var stages = 0.0
        var items = 0.0
        var status = 0.0
        val stageValues = hashMapOf<Pair<String, Set<java.util.UUID>>, Double>()
        val itemValues = hashMapOf<String, Double>()
        val conditionValues = hashMapOf<String, Double>()
        val originalKey = cache.fingerprints.of(context.state)
        for (path in paths) {
            val body = path.bodyState.pokemon.associateBy { it.battlePokemonId }
            val after = path.state.pokemon.associateBy { it.battlePokemonId }
            hp += path.probability * path.state.pokemon.sumOf { next ->
                val previous = body.getValue(next.battlePokemonId)
                (next.hpFraction - previous.hpFraction) * if (next.side == BattleSide.ALLY) 1.0 else -1.0
            } * tuning.boardToScore
            knockout += path.probability * path.state.pokemon.sumOf { next ->
                val previous = body.getValue(next.battlePokemonId)
                val difference = (if (next.fainted || next.hpFraction <= 0.0) 1.0 else 0.0) -
                    (if (previous.fainted || previous.hpFraction <= 0.0) 1.0 else 0.0)
                if (difference == 0.0) 0.0 else difference * LocalPokemonWorth.knockoutScore(next.battlePokemonId, tuning) *
                    if (next.side == BattleSide.ALLY) -1.0 else 1.0
            }
            // The same pre-hit HP in both boards. A knockout neither deletes the stage comparison
            // nor pays for the removed HP a second time. Only living recipients retain a reaction.
            fun changed(stage: Boolean, item: Boolean, condition: Boolean): BattleStateView {
                val pokemon = context.state.pokemon.map { original ->
                    val before = body[original.battlePokemonId] ?: return@map original
                    val next = after[original.battlePokemonId] ?: return@map original
                    if (next.fainted || next.hpFraction <= 0.0) return@map original
                    val statDelta = if (!stage) emptyMap() else (before.statStages.keys + next.statStages.keys).associateWith {
                        (next.statStages[it] ?: 0) - (before.statStages[it] ?: 0)
                    }.filterValues { it != 0 }
                    val statStages = if (statDelta.isEmpty()) original.statStages else original.statStages.toMutableMap().apply {
                        statDelta.forEach { (stat, delta) -> this[stat] = ((this[stat] ?: 0) + delta).coerceIn(-6, 6) }
                    }
                    val heldItem = if (item && before.knownHeldItemId != next.knownHeldItemId) next.knownHeldItemId else original.knownHeldItemId
                    val statusId = if (condition && before.statusId != next.statusId) next.statusId else original.statusId
                    val effects = if (condition) (original.knownVolatileEffectIds - (before.knownVolatileEffectIds - next.knownVolatileEffectIds)) +
                        ((next.knownVolatileEffectIds - before.knownVolatileEffectIds) - LocalReactiveAbilityState.BOOSTED_THIS_TURN) else original.knownVolatileEffectIds
                    // Keep copyState's inactive/fainted cleanup even when the reaction changed nothing.
                    val retainedEffects = if (original.fainted || original.activeSlot == null)
                        effects.filter { it.startsWith(LocalBerryMechanics.LAST_CONSUMED_ITEM) }.toSet() else effects
                    if (statStages == original.statStages && heldItem == original.knownHeldItemId &&
                        statusId == original.statusId && retainedEffects == original.knownVolatileEffectIds) original
                    else original.copyState(statStages = statStages, knownHeldItemId = heldItem,
                        statusId = statusId, knownVolatileEffectIds = retainedEffects)
                }
                return if (pokemon.indices.all { pokemon[it] === context.state.pokemon[it] }) context.state
                    else context.state.copyState(pokemon = pokemon)
            }
            val raised = changed(stage = true, item = false, condition = false)
            val knockedOutFoes = path.state.pokemon.filter { it.side == BattleSide.OPPONENT &&
                (it.fainted || it.hpFraction <= 0.0) && context.state.pokemon.firstOrNull { old ->
                    old.battlePokemonId == it.battlePokemonId }?.let { old -> !old.fainted && old.hpFraction > 0.0 } == true
            }.map { it.battlePokemonId }.toSet()
            val stageValue = stageValues.getOrPut(cache.fingerprints.of(raised) to knockedOutFoes) { if (knockedOutFoes.isEmpty()) {
                LocalStatStageMarginalEvaluator.evaluate(context.state, raised, context, cache, tuning).score
            } else {
                // KO abilities are useful against the remaining opponents, not the one already beaten.
                val users = raised.pokemon.filter { user -> user.side == BattleSide.ALLY && !user.fainted &&
                    user.statStages != context.state.pokemon.first { it.battlePokemonId == user.battlePokemonId }.statStages }
                val foes = context.state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted &&
                    it.hpFraction > 0.0 && it.battlePokemonId !in knockedOutFoes }
                users.sumOf { user -> foes.mapNotNull { foe ->
                    val originalUser = context.state.pokemon.first { it.battlePokemonId == user.battlePokemonId }
                    val face = LocalMatchupPosition.face(context, originalUser, foe, cache) ?: return@mapNotNull null
                    val projected = face.state.copyState(pokemon = face.state.pokemon.map {
                        if (it.battlePokemonId == user.battlePokemonId) it.copyState(statStages = user.statStages) else it })
                    LocalStatStageMarginalEvaluator.evaluate(face.state, projected, face, cache, tuning).score
                }.let { if (it.isEmpty()) 0.0 else it.average() } }
            } }
            stages += path.probability * stageValue
            val itemState = changed(stage = false, item = true, condition = false)
            items += path.probability * itemValues.getOrPut(cache.fingerprints.of(itemState)) {
                LocalItemTransitionEvaluator.evaluate(context.state, itemState, context, cache, tuning).total }
            val statusState = changed(stage = false, item = false, condition = true)
            if (cache.fingerprints.of(statusState) != originalKey) {
                fun pressure(state: BattleStateView) = LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.ALLY,
                    context, cache, tuning = tuning, capDamageToRemainingHp = true) -
                    LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.OPPONENT,
                        context, cache, tuning = tuning, capDamageToRemainingHp = true)
                status += path.probability * conditionValues.getOrPut(cache.fingerprints.of(statusState)) {
                    (pressure(statusState) - pressure(context.state) +
                    LocalStatStageMarginalEvaluator.speedTransitionBoardDelta(context.state, statusState, tuning, context, cache)) * tuning.boardToScore }
            }
        }
        val total = hp + knockout + stages + items + status
        LocalDecisionInternals.record("hitReaction:$actionId", mapOf("hp" to hp, "knockout" to knockout,
            "stage" to stages, "item" to items, "status" to status, "branches" to paths.size.toDouble(), "value" to total))
        return Score(total, stages, hp + knockout + items + status)
    }
}
