package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import jbro.cobblemon.mcc.betterai.state.LocalFieldEffectProjector

internal data class LocalMoveHitSequenceResult(val state: BattleStateView, val probability: Double,
    val directDamageFraction: Double, val recoilHpFraction: Double, val substituteInterceptedWholeMove: Boolean, val receivedHits: List<LocalReceivedMoveHit> = emptyList())

/** Hit callbacks and Update run between individual hits, so a broken decoy cannot block later hits. */
internal object LocalMoveHitSequence {
    fun project(initial: BattleStateView, actorId: UUID, targetId: UUID?, action: BattleActionCandidate,
                totalDamage: Double, effects: List<BattleMoveEffectView>, bypassesSubstitute: Boolean,
                source: BattleDecisionContext, side: BattleSide, userEffects: Boolean = true,
                hitCountOverride: Int? = null, rollPercentile: Double? = null): List<LocalMoveHitSequenceResult> {
        val actor = initial.pokemon.firstOrNull { it.battlePokemonId == actorId } ?: return emptyList()
        val beatUpMembers = if (PublicIds.canonical(action.moveId.orEmpty()) == "beatup") initial.pokemon.filter {
            it.side == actor.side && !it.fainted && it.hpFraction > 0.0 && it.statusId == null
        } else emptyList()
        val count = hitCountOverride ?: if (beatUpMembers.isNotEmpty()) beatUpMembers.size else
            LocalDeclaredMultiHit.representativeCount(action, actor, initial)
        val perHitCalculation = beatUpMembers.isNotEmpty() || LocalDeclaredMultiHit.maximumCount(action) > 1
        val moveStartActor = source.state.pokemon.firstOrNull { it.battlePokemonId == actorId }
        fun calculationState(current: BattleStateView): BattleStateView = if (moveStartActor == null ||
            PublicIds.canonical(moveStartActor.knownTeraTypeId.orEmpty()) != "stellar") current else current.copyState(
            pokemon = current.pokemon.map { if (it.battlePokemonId == actorId) it.copyState(
                knownStellarBoostedTypeIds = moveStartActor.knownStellarBoostedTypeIds) else it })
        val percentile = rollPercentile ?: if (perHitCalculation) {
            val original = PublicBattleTacticalCalculator.conservativeDamageRollFractions(action,
                source.copy(state = calculationState(initial), candidates = listOf(action)), side)?.sorted().orEmpty()
            val reference = if (LocalDeclaredMultiHit.usesPerHitAccuracy(action)) totalDamage / count else totalDamage
            val median = (original.size - 1) / 2
            val index = if (original.isEmpty()) 0 else if (abs(original[median] - reference) < 1e-9) median else
                original.indices.minBy { abs(original[it] - reference) }
            if (original.size <= 1) 0.5 else index.toDouble() / (original.size - 1)
        } else 0.5
        var branches = listOf(LocalMoveHitSequenceResult(initial, 1.0, 0.0, 0.0, true))
        repeat(count) { index ->
            branches = branches.flatMap { previous ->
                val target = previous.state.pokemon.firstOrNull { it.battlePokemonId == targetId }
                val currentActor = previous.state.pokemon.firstOrNull { it.battlePokemonId == actorId }
                if (target?.fainted == true || currentActor?.fainted == true) return@flatMap listOf(previous)
                val damage = if (!perHitCalculation) totalDamage / count else {
                    val tagged = BattleActionCandidate(action.actionId, action.kind, actorSlot = action.actorSlot, moveSlot = action.moveSlot,
                        moveId = action.moveId, targets = action.targets, mechanic = action.mechanic, moveDetails = action.moveDetails,
                        tags = action.tags + setOf("better_ai:single_hit", "better_ai:hit_index=${index + 1}") +
                            beatUpMembers.getOrNull(index)?.let { setOf("better_ai:beatup_member=${it.battlePokemonId}") }.orEmpty())
                    val context = source.copy(state = calculationState(previous.state), candidates = listOf(tagged))
                    PublicBattleTacticalCalculator.conservativeDamageRollFractions(tagged, context, side)?.sorted()?.let {
                        it[((it.size - 1) * percentile.coerceIn(0.0, 1.0)).roundToInt()]
                    } ?: totalDamage / count
                }
                // A decoy whose remaining HP is not known may or may not survive this hit: one branch for each.
                val hypotheses = if (bypassesSubstitute || targetId == actorId) listOf(previous.state to 1.0)
                    else LocalPersistentMoveState.substituteHypotheses(previous.state, targetId, damage)
                hypotheses.flatMap { (hitState, chance) ->
                    val hadDecoy = !bypassesSubstitute && target?.knownVolatileEffectIds?.contains("substitute") == true
                    val hitEffects = effects.filter { effect ->
                        effect.kind !in setOf(BattleMoveEffectKind.SELF_DESTRUCT, BattleMoveEffectKind.HEAL_FRACTION) || index == count - 1
                    }
                    val direct = LocalDirectHitMechanics.apply(hitState, actorId, targetId, damage, hitEffects,
                        LocalPublicAbilityMechanics.ignoresTargetAbility(action, currentActor, target, hitState),
                        bypassesSubstitute, updateItems = false)
                    val onHit = LocalPersistentMoveState.afterHit(direct.state, actorId, targetId, action, !hadDecoy)
                    val reacted = hitFieldReactions(LocalAfterHitReactions.apply(hitState, onHit, actorId, targetId, action,
                        direct.directDamageFraction, userEffects = userEffects && index == count - 1),
                        targetId, action, direct.directDamageFraction, hadDecoy).let {
                            raisedThisTurnSecondary(it, actorId, targetId, action, direct.directDamageFraction, hadDecoy) }
                    LocalContactAfterHitMechanics.project(reacted, actorId, targetId, action, direct.directDamageFraction).map {
                        LocalMoveHitSequenceResult(LocalBerryMechanics.afterUpdate(it.state), previous.probability * chance * it.probability,
                            previous.directDamageFraction + direct.directDamageFraction,
                            previous.recoilHpFraction + direct.recoilHpFraction,
                            previous.substituteInterceptedWholeMove && hadDecoy,
                            previous.receivedHits + if (targetId != null && direct.directDamageFraction > 0.0)
                                listOf(LocalReceivedMoveHit(actorId, targetId, direct.directDamageFraction,
                                    action.moveDetails?.damageCategory ?: BattleMoveDamageCategory.STATUS,
                                    currentActor?.side, currentActor?.activeSlot)) else emptyList())
                    }
                }
            }
        }
        return branches
    }

    /**
     * Alluring Voice confuses and Burning Jealousy burns a target whose stats rose this turn: a secondary effect, so a
     * Substitute, Shield Dust, a Covert Cloak and the user's Sheer Force stop it.
     */
    private fun raisedThisTurnSecondary(
        state: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        action: BattleActionCandidate,
        directDamage: Double,
        substituteTookHit: Boolean,
    ): BattleStateView {
        val moveId = PublicIds.canonical(action.moveId.orEmpty())
        if (moveId != "alluringvoice" && moveId != "burningjealousy" || directDamage <= 0.0 || substituteTookHit) return state
        val target = targetId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } } ?: return state
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorId }
        if (target.fainted || target.hpFraction <= 0.0 || LocalReactiveAbilityState.BOOSTED_THIS_TURN !in target.knownVolatileEffectIds) return state
        if (actor != null && LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "sheerforce") return state
        if (LocalPublicAbilityState.effectiveKnownAbility(state, target) == "shielddust" ||
            LocalPublicItemState.activeItemId(state, target) == "covertcloak") return state
        val updated = if (moveId == "alluringvoice") {
            if (target.knownVolatileEffectIds.any { PublicIds.canonical(it) == "confusion" } ||
                LocalPublicAbilityState.effectiveKnownAbility(state, target) == "owntempo" ||
                LocalPublicFieldMechanics.terrainId(state) == "mistyterrain" && LocalPublicTurnOrder.grounded(state, target)) return state
            target.copyState(knownVolatileEffectIds = target.knownVolatileEffectIds + "confusion")
        } else {
            if (target.statusId != null || LocalPublicStatusImmunity.blocked(state, target, "brn", actor)) return state
            target.copyState(statusId = "brn")
        }
        return state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == target.battlePokemonId) updated else it })
    }

    /** Toxic Debris lays Toxic Spikes under a physical attacker and Seed Sower spreads Grassy Terrain, on each hit. */
    private fun hitFieldReactions(
        state: BattleStateView,
        targetId: UUID?,
        action: BattleActionCandidate,
        directDamage: Double,
        substituteTookHit: Boolean,
    ): BattleStateView {
        if (directDamage <= 0.0 || substituteTookHit) return state
        val target = targetId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } } ?: return state
        return when (LocalPublicAbilityState.effectiveKnownAbility(state, target)) {
            "toxicdebris" -> if (action.moveDetails?.damageCategory == BattleMoveDamageCategory.PHYSICAL) {
                LocalFieldEffectProjector.apply(
                    state, target.side,
                    BattleMoveEffectView(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.TARGET_SIDE, 1.0, "toxicspikes"),
                    target.battlePokemonId,
                )
            } else state
            "seedsower" -> LocalFieldEffectProjector.apply(
                state, target.side,
                BattleMoveEffectView(BattleMoveEffectKind.TERRAIN, BattleMoveEffectTarget.FIELD, 1.0, "grassyterrain"),
                target.battlePokemonId,
            )
            else -> state
        }
    }
}
