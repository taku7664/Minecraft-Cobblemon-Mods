package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.betterai.calculation.*
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.internal.ai.*

/** One move's immediate mechanical state, without an opponent's reply or turn-end events.
 * bodyState retains the same damage/recoil/drain but no hit reactions, so root valuation can
 * price only the additional effects. Switch valuation reads the full resulting state.
 */
internal object LocalMoveImpactProjector {
    data class Path(val state: BattleStateView, val bodyState: BattleStateView, val probability: Double,
        val directDamage: Double = 0.0, val connected: Boolean = false)

    fun mayReact(action: BattleActionCandidate, context: BattleDecisionContext, side: BattleSide,
        includeGuards: Boolean = true, ignoreActorItem: Boolean = false): Boolean {
        val state = context.state
        val actor = state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot } ?: return false
        val targets = LocalPublicMoveTargets.resolve(action, context, side)
        val hooks = if (includeGuards) REACTION_HOOKS else STATE_REACTION_HOOKS
        return runCatching {
            val dex = EngineRuntimeDex.current().second
            (targets + actor).any { holder ->
                val holderHooks = if (holder.battlePokemonId == actor.battlePokemonId) hooks else hooks - USER_ONLY_HOOKS
                val ability = LocalPublicAbilityState.effectiveKnownAbility(state, holder)
                val item = if (ignoreActorItem && holder.battlePokemonId == actor.battlePokemonId) null
                    else LocalPublicItemState.activeItemId(state, holder)
                ability != null && holderHooks.any { dex.ability(ability).declares(it) } ||
                    item != null && holderHooks.any { dex.item(item).declares(it) }
            } || !includeGuards && targets.any { target -> LocalMoveAbsorption.apply(state, action, actor, target) != null } ||
                state.pokemon.any { holder -> holder.activeSlot != null && !holder.fainted &&
                LocalPublicAbilityState.effectiveKnownAbility(state, holder)?.let { dex.ability(it).declares("onAnyFaint") } == true } ||
                action.moveId?.let { id -> dex.move(PublicIds.canonical(id))?.let { move ->
                    move.declares("onAfterHit") || move.declares("onHit") } } == true
        }.getOrDefault(true)
    }

    fun project(action: BattleActionCandidate, context: BattleDecisionContext, side: BattleSide,
        cache: LocalProjectedActionCalculationCache, initialBody: BattleStateView = context.state): List<Path> {
        val actor = context.state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot && !it.fainted }
            ?: return listOf(Path(context.state, initialBody, 1.0))
        if (LocalTruant.loafs(context.state, actor)) {
            fun loafed(state: BattleStateView) = state.copyState(pokemon = state.pokemon.map {
                if (it.battlePokemonId == actor.battlePokemonId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds - "truant") else it
            })
            return listOf(Path(loafed(context.state), loafed(initialBody), 1.0))
        }
        val targets = LocalPublicMoveTargets.resolve(action, context, side)
        var paths = listOf(Path(context.state, initialBody, 1.0))
        for ((index, declaredTarget) in targets.withIndex()) {
            paths = paths.flatMap { parent ->
                val source = parent.state.pokemon.firstOrNull { it.battlePokemonId == actor.battlePokemonId && !it.fainted }
                    ?: return@flatMap listOf(parent)
                val target = parent.state.pokemon.firstOrNull { it.battlePokemonId == declaredTarget.battlePokemonId && !it.fainted }
                    ?: return@flatMap listOf(parent)
                val aimed = if (targets.size == 1) action else LocalPublicMoveTargets.spreadHitOn(action, target, "${action.actionId}:impact:$index")
                val calculated = cache.getOrCalculate(parent.state, side, aimed, context.publicActionCatalog) {
                    PublicBattleTacticalCalculator.calculate(context.copy(state = parent.state, candidates = listOf(aimed)), side)
                }
                val candidate = calculated.candidates.single()
                val absorption = LocalMoveAbsorption.apply(parent.state, candidate, source, target)
                if (absorption != null) return@flatMap listOf(parent.copy(state = absorption))
                if (LocalPublicMechanicsKernel.projectMove(candidate, calculated, side).publiclyNullified ||
                    candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) return@flatMap listOf(parent)
                val counts = LocalDeclaredMultiHit.countDistribution(candidate, source, parent.state)
                val splitCounts = counts.keys.any { it > 1 } && !LocalDeclaredMultiHit.usesPerHitAccuracy(candidate)
                val rollAction = if (splitCounts) BattleActionCandidate(candidate.actionId, candidate.kind,
                    actorSlot = candidate.actorSlot, moveSlot = candidate.moveSlot, moveId = candidate.moveId,
                    targets = candidate.targets, mechanic = candidate.mechanic, moveDetails = candidate.moveDetails,
                    facts = candidate.facts, tags = candidate.tags + "better_ai:single_hit") else candidate
                val outcomes = PublicMoveOutcomeBranchProjector.withChanceModel(LocalChanceModel.EXACT_ROLLS) {
                    PublicMoveOutcomeBranchProjector.project(rollAction, calculated, side)
                }.flatMap { outcome ->
                    if (!splitCounts || !outcome.hit) listOf(outcome) else counts.map { (count, chance) ->
                        outcome.copy(probability = outcome.probability * chance, damageFraction = outcome.damageFraction * count, hitCount = count) }
                }
                outcomes.flatMap { outcome ->
                    if (!outcome.hit) return@flatMap listOf(parent.copy(probability = parent.probability * outcome.probability))
                    val hit = if (outcome.critical) LocalCriticalHitRules.asCritical(candidate) else candidate
                    val effects = candidate.moveDetails?.effects?.effects.orEmpty().filter {
                        // Chance effects are valued by their own modules. DirectHit reads HP effects; no rank bonus is invented here.
                        (it.probability ?: 1.0) >= 1.0
                    }
                    val bypass = LocalSubstituteRules.bypasses(hit, source, parent.state)
                    val bodyDamage = if (LocalDeclaredMultiHit.maximumCount(hit) > 1 || parent.state === parent.bodyState) outcome.damageFraction else {
                        val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(hit,
                            calculated.copy(state = parent.bodyState), side)?.sorted()
                        rolls?.get(kotlin.math.round((rolls.size - 1) * (outcome.rollPercentile ?: 0.5)).toInt()) ?: outcome.damageFraction
                    }
                    val bodies = LocalMoveHitSequence.project(parent.bodyState, source.battlePokemonId, target.battlePokemonId, hit,
                        bodyDamage, effects, bypass, calculated.copy(state = parent.bodyState), side,
                        hitCountOverride = outcome.hitCount, rollPercentile = outcome.rollPercentile, reactions = false)
                    LocalMoveHitSequence.project(parent.state, source.battlePokemonId, target.battlePokemonId, hit,
                        outcome.damageFraction, effects, bypass, calculated, side,
                        hitCountOverride = outcome.hitCount, rollPercentile = outcome.rollPercentile, finishMove = false).flatMap { result ->
                        bodies.map { body -> Path(result.state, body.state,
                            parent.probability * outcome.probability * result.probability * body.probability,
                            parent.directDamage + result.directDamageFraction,
                            parent.connected || result.directDamageFraction > 0.0 ||
                                !bypass && "substitute" in target.knownVolatileEffectIds && outcome.damageFraction > 0.0) }
                    }
                }
            }
            // Damage rolls and stochastic callbacks that lead to the same public state share the continuation.
            paths = paths.groupBy { Triple(cache.fingerprints.keyOf(it.state), cache.fingerprints.keyOf(it.bodyState), it.connected) }.values.map { same ->
                same.first().copy(probability = same.sumOf { it.probability })
            }
        }
        paths = paths.map { it.copy(state = LocalAfterHitReactions.afterMove(it.state, actor.battlePokemonId, action, it.connected)) }
        return if (!LocalTruant.active(context.state, actor)) paths else paths.map { path ->
            fun attempted(state: BattleStateView) = state.copyState(pokemon = state.pokemon.map {
                if (it.battlePokemonId == actor.battlePokemonId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + "truant") else it
            })
            path.copy(state = attempted(path.state), bodyState = attempted(path.bodyState))
        }
    }

    private val REACTION_HOOKS = setOf("onTryHit", "onDamage", "onDamagingHit", "onHit", "onUpdate",
        "onAfterMoveSecondary", "onAfterMoveSecondarySelf", "onSourceAfterFaint", "onAfterUseItem", "onBeforeMove")
    // Pure guards are already decided by the hit kernel. Absorbing TryHit effects are checked above.
    private val STATE_REACTION_HOOKS = REACTION_HOOKS - setOf("onTryHit", "onBeforeMove")
    private val USER_ONLY_HOOKS = setOf("onAfterMoveSecondarySelf", "onSourceAfterFaint", "onBeforeMove")
}
