package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID

/** Item-only action paths shared by the root's individual and joint evaluations. */
internal object LocalPublicItemEffectProjector {
    data class Path(val state: BattleStateView, val probability: Double,
        /** A damaging reaction's stage owner; acquired White Herb restoration remains item-owned. */
        val damagingStageOwnerIds: Set<UUID> = emptySet())

    fun project(candidate: BattleActionCandidate, context: BattleDecisionContext,
        targetIds: Set<UUID>? = null, accuracy: Double? = null): List<Path> {
        val original = Path(context.state, 1.0)
        val initialActor = actor(candidate, context.state) ?: return listOf(original)
        val targets = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).withIndex()
            .filter { targetIds == null || it.value.battlePokemonId in targetIds }
        if (targets.isEmpty()) return listOf(original)
        val chance = (accuracy ?: LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY)).coerceIn(0.0, 1.0)
        val moveId = PublicIds.canonical(candidate.moveId.orEmpty())
        if (moveId in ITEM_SWAPS) {
            val target = targets.singleOrNull()?.value ?: return listOf(original)
            if (!LocalPublicItemTransferRules.canSwap(context.state, initialActor, target,
                    LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, initialActor, target, context.state),
                    LocalSubstituteRules.bypasses(candidate, initialActor, context.state)) ||
                LocalPublicMechanicsKernel.projectMove(candidate, context).publiclyNullified
            ) return listOf(original)
            val after = LocalPublicItemTransferRules.swap(context.state, initialActor, target)
            return (if (chance > 0.0) listOf(Path(after, chance)) else emptyList()) +
                if (chance < 1.0) listOf(original.copy(probability = 1.0 - chance)) else emptyList()
        }
        if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) return listOf(original)
        var hits = if (chance > 0.0) listOf(original.copy(probability = chance)) else emptyList()
        val fingerprints = LocalBattleStateFingerprint()
        targets.forEach { (index, target) ->
            val action = targetHit(candidate, target, index, context)
            hits = hits.flatMap { path ->
                val currentActor = actor(candidate, path.state) ?: return@flatMap listOf(path)
                val targeted = context.copy(state = path.state, candidates = listOf(action))
                val mechanics = LocalPublicMechanicsKernel.projectMove(action, targeted)
                if (mechanics.publiclyNullified) return@flatMap listOf(path)
                val rolls = damageHypotheses(action, targeted, mechanics.knownDamageMultiplier)
                if (rolls.isEmpty()) return@flatMap listOf(path)
                val current = path.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
                val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(action, currentActor, current, path.state)
                rolls.groupingBy { it }.eachCount().map { (damage, count) ->
                    val (after, stageOwner) = itemHit(path.state, currentActor, current, action, damage, ignoresAbility,
                        LocalSubstituteRules.bypasses(action, currentActor, path.state),
                        LocalDeclaredMultiHit.representativeCount(action, currentActor, path.state))
                    Path(after, path.probability * count / rolls.size, path.damagingStageOwnerIds + stageOwner)
                }
            }.groupBy { fingerprints.of(it.state) to it.damagingStageOwnerIds }.values.map { same ->
                same.first().copy(probability = same.sumOf { it.probability })
            }
        }
        return hits + if (chance < 1.0) listOf(original.copy(probability = 1.0 - chance)) else emptyList()
    }

    private fun actor(candidate: BattleActionCandidate, state: BattleStateView) = state.pokemon.firstOrNull {
        it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
    }

    private fun itemHit(state: BattleStateView, actor: BattlePokemonStateView, target: BattlePokemonStateView,
        action: BattleActionCandidate, damage: Double, ignoresAbility: Boolean, bypassesSubstitute: Boolean,
        hitCount: Int): Pair<BattleStateView, Set<UUID>> {
        val hit = LocalDirectHitMechanics.apply(state, actor.battlePokemonId, target.battlePokemonId,
            damage, emptyList(), ignoresAbility, bypassesSubstitute, hitCount)
        if (hit.directDamageFraction <= 0.0) return hit.state to emptySet()
        val struck = hit.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        var after = hit.state
        var stageOwner = emptySet<UUID>()
        if (!struck.fainted && struck.hpFraction > 0.0) {
            if (LocalPublicItemState.activeItemId(after, struck) == "airballoon") after = setItem(after, struck, "")
            if (LocalAfterHitReactions.weaknessPolicyActivates(after, struck, action, actor)) {
                after = LocalStatStageChange.apply(after, struck.battlePokemonId, null, mapOf("attack" to 2, "special_attack" to 2))
                after = setItem(after, struck, "")
                stageOwner = setOf(struck.battlePokemonId)
            }
        }
        val current = after.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        if (PublicIds.canonical(action.moveId.orEmpty()) == "knockoff" &&
            LocalPublicItemTransferRules.canRemove(after, current, ignoresAbility)) after = setItem(after, current, "")
        return after to stageOwner
    }

    private fun targetHit(candidate: BattleActionCandidate, target: BattlePokemonStateView, index: Int,
        context: BattleDecisionContext): BattleActionCandidate {
        if (index == 0 && (candidate.targets.isNotEmpty() || candidate.facts?.spreadTargets.isNullOrEmpty())) return candidate
        val targeted = LocalPublicMoveTargets.spreadHitOn(candidate, target, "${candidate.actionId}:item:$index")
        val original = candidate.facts
        val extra = original?.spreadTargets?.firstOrNull { it.side == target.side && it.slot == target.activeSlot }
        val complete = original?.standardDamageModel != null && extra?.standardDamageFractionRange != null &&
            extra.standardDamageRollKoProbabilityRange != null && extra.standardKnockoutAssessment != null
        val facts = if (extra == null || original == null) null else original.copy(
            typeChartMultiplier = extra.typeChartMultiplier,
            standardDamageModel = if (complete) original.standardDamageModel else null,
            standardDamageFractionRange = if (complete) extra.standardDamageFractionRange else null,
            standardDamageRollKoProbabilityRange = if (complete) extra.standardDamageRollKoProbabilityRange else null,
            standardKnockoutAssessment = if (complete) extra.standardKnockoutAssessment else null,
            spreadTargets = emptyList(),
        )
        return BattleActionCandidate(targeted.actionId, targeted.kind, targeted.actorSlot, targeted.moveSlot, targeted.moveId,
            targets = targeted.targets, mechanic = targeted.mechanic, moveDetails = targeted.moveDetails, facts = facts,
            tags = if (LocalPublicMoveTargets.spreadMultiplier(candidate, context, BattleSide.ALLY) < 1.0)
                targeted.tags else candidate.tags)
    }

    private fun damageHypotheses(candidate: BattleActionCandidate, context: BattleDecisionContext, multiplier: Double): List<Double> {
        val range = candidate.facts?.standardDamageFractionRange
        // Formula facts read the real public rolls; direct provider facts retain their explicit bounds.
        if (range == null || BattleCalculationBasis.SHOWDOWN_GEN9_FORMULA in candidate.facts?.basis.orEmpty()) {
            PublicBattleTacticalCalculator.conservativeDamageRollFractions(candidate, context, BattleSide.ALLY)
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        range ?: return emptyList()
        return if (range.minimum == range.maximum) listOf(range.minimum * multiplier)
            else listOf(range.minimum * multiplier, range.maximum * multiplier)
    }

    private fun setItem(state: BattleStateView, pokemon: BattlePokemonStateView, item: String) =
        state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == pokemon.battlePokemonId) it.copyState(knownHeldItemId = item) else it })

    private val ITEM_SWAPS = setOf("trick", "switcheroo")
}
