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
                val hadDecoy = !bypassesSubstitute && target?.knownVolatileEffectIds?.contains("substitute") == true
                val hitEffects = effects.filter { effect ->
                    effect.kind !in setOf(BattleMoveEffectKind.SELF_DESTRUCT, BattleMoveEffectKind.HEAL_FRACTION) || index == count - 1
                }
                val direct = LocalDirectHitMechanics.apply(previous.state, actorId, targetId, damage, hitEffects,
                    LocalPublicAbilityMechanics.ignoresTargetAbility(action, currentActor, target, previous.state),
                    bypassesSubstitute, updateItems = false)
                val onHit = LocalPersistentMoveState.afterHit(direct.state, actorId, targetId, action, !hadDecoy)
                val reacted = hitFieldReactions(LocalAfterHitReactions.apply(previous.state, onHit, actorId, targetId, action,
                    direct.directDamageFraction, userEffects = userEffects && index == count - 1),
                    targetId, action, direct.directDamageFraction, hadDecoy)
                LocalContactAfterHitMechanics.project(reacted, actorId, targetId, action, direct.directDamageFraction).map {
                    LocalMoveHitSequenceResult(LocalBerryMechanics.afterUpdate(it.state), previous.probability * it.probability,
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
        return branches
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
