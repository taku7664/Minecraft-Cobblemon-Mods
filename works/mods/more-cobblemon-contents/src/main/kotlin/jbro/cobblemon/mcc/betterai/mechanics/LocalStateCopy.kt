package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * Structural copies of the immutable public state views.
 *
 * Both the turn projector and the recursive history need these. While they were file-private helpers
 * at the bottom of the combined search file that was invisible; splitting the file surfaced the shared
 * dependency, which is the point of splitting it.
 *
 * The remaining-count bookkeeping matters: `remainingPokemonBySide` counts Pokemon the AI has not
 * seen as well as the ones it has, so it is adjusted by the delta in known living members rather than
 * recounted from the visible list.
 */
internal fun BattleStateView.copyState(
    turn: Int = this.turn,
    pokemon: List<BattlePokemonStateView> = this.pokemon,
): BattleStateView = derive(
    turn = turn,
    pokemon = pokemon,
    remainingPokemonBySide = BattleSide.entries.associateWith { side ->
        val previousKnownLiving = this.pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        val nextKnownLiving = pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        (this.remainingPokemonBySide.getValue(side) + nextKnownLiving - previousKnownLiving).coerceAtLeast(0)
    },
)

internal fun BattlePokemonStateView.copyState(
    side: BattleSide = this.side,
    activeSlot: Int? = this.activeSlot,
    hpFraction: Double = this.hpFraction,
    statusId: String? = this.statusId,
    statStages: Map<String, Int> = this.statStages,
    fainted: Boolean = this.fainted,
    actionConstraints: BattlePokemonActionConstraintView = this.actionConstraints,
    knownVolatileEffectIds: Set<String> = this.knownVolatileEffectIds,
    knownMoveIds: Set<String> = this.knownMoveIds,
    knownTypeIds: Set<String> = this.knownTypeIds,
    knownBaseStabTypeIds: Set<String> = this.knownBaseStabTypeIds,
    knownTeraTypeId: String? = this.knownTeraTypeId,
    knownStellarBoostedTypeIds: Set<String>? = this.knownStellarBoostedTypeIds,
    combatStats: BattleCombatStatRangesView? = this.combatStats,
    knownAbilityId: String? = this.knownAbilityId,
    knownHeldItemId: String? = this.knownHeldItemId,
): BattlePokemonStateView = BattlePokemonStateView(
    battlePokemonId = battlePokemonId,
    side = side,
    activeSlot = activeSlot,
    speciesId = speciesId,
    formId = formId,
    level = level,
    hpFraction = hpFraction,
    statusId = statusId,
    statStages = statStages,
    knownMoveIds = knownMoveIds,
    knownAbilityId = knownAbilityId,
    knownHeldItemId = knownHeldItemId,
    fainted = fainted,
    knownTypeIds = knownTypeIds,
    combatStats = combatStats,
    knownFormStates = knownFormStates,
    actionConstraints = actionConstraints,
    knownVolatileEffectIds = if (fainted || activeSlot == null) emptySet() else knownVolatileEffectIds,
    knownBaseStabTypeIds = knownBaseStabTypeIds,
    knownTeraTypeId = knownTeraTypeId,
    knownStellarBoostedTypeIds = knownStellarBoostedTypeIds,
)
