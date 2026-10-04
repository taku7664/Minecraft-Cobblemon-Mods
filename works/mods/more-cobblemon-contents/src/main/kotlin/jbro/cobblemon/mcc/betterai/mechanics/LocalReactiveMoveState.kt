package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID
import kotlin.math.floor

internal data class LocalReceivedMoveHit(val sourceId: UUID, val targetId: UUID,
    val damageFraction: Double, val category: BattleMoveDamageCategory,
    val sourceSide: BattleSide? = null, val sourceSlot: Int? = null)

/** Counter attacks use the preceding enemy hit, rather than a guessed move power. */
internal object LocalReactiveMoveState {
    fun resolve(state: BattleStateView, side: BattleSide, action: BattleActionCandidate,
                hits: List<LocalReceivedMoveHit>): BattleActionCandidate {
        val move = PublicIds.canonical(action.moveId.orEmpty())
        if (move !in setOf("counter", "mirrorcoat", "metalburst")) return action
        val user = state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot && !it.fainted } ?: return action
        val hit = hits.lastOrNull { hit ->
            hit.targetId == user.battlePokemonId && hit.damageFraction > 0.0 &&
                state.pokemon.any { it.battlePokemonId == hit.sourceId && it.side != side } &&
                (move == "metalburst" || hit.category == if (move == "counter") BattleMoveDamageCategory.PHYSICAL else BattleMoveDamageCategory.SPECIAL)
        } ?: return action
        // Native Counter redirects to the attacker's slot, including the replacement after a pivot.
        val target = state.pokemon.firstOrNull {
            if (hit.sourceSide != null && hit.sourceSlot != null) it.side == hit.sourceSide && it.activeSlot == hit.sourceSlot && !it.fainted
            else it.battlePokemonId == hit.sourceId && it.activeSlot != null && !it.fainted
        } ?: return action
        val hp = user.combatStats?.maxHp ?: return action
        val factor = if (move == "metalburst") 1.5 else 2.0
        val minimum = floor(hit.damageFraction * hp.minimum * factor + 1e-9).toInt().coerceAtLeast(1)
        val maximum = floor(hit.damageFraction * hp.maximum * factor + 1e-9).toInt().coerceAtLeast(minimum)
        val details = action.moveDetails ?: return action
        val effects = details.effects
        return BattleActionCandidate(action.actionId, action.kind, actorSlot = action.actorSlot, moveSlot = action.moveSlot,
            moveId = action.moveId, targets = listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot))),
            mechanic = action.mechanic, moveDetails = details.copy(effects = BattleMoveEffectsView(
                effects?.coverage ?: BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects?.effects.orEmpty() + BattleMoveEffectView(BattleMoveEffectKind.FIXED_DAMAGE_VALUE,
                    BattleMoveEffectTarget.SELECTED_TARGET, amountRange = BattleIntegerRange(minimum, maximum)),
                effects?.scriptedBehavior ?: true,
                effects?.requirements.orEmpty().filterNot { it.kind == BattleMoveRequirementKind.PRIOR_DAMAGE_THIS_TURN },
                effects?.mechanicFlags.orEmpty(),
            )), tags = action.tags)
    }
}
