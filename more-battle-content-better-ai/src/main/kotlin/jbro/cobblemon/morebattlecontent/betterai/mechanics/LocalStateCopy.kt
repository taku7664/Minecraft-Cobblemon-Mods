package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

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
): BattleStateView = BattleStateView(
    battleId = battleId,
    format = format,
    turn = turn,
    pokemon = pokemon,
    field = field,
    remainingPokemonBySide = BattleSide.entries.associateWith { side ->
        val previousKnownLiving = this.pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        val nextKnownLiving = pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        (this.remainingPokemonBySide.getValue(side) + nextKnownLiving - previousKnownLiving).coerceAtLeast(0)
    },
    observedEvents = observedEvents,
    inferences = inferences,
)

internal fun BattlePokemonStateView.copyState(
    side: BattleSide = this.side,
    activeSlot: Int? = this.activeSlot,
    hpFraction: Double = this.hpFraction,
    statusId: String? = this.statusId,
    statStages: Map<String, Int> = this.statStages,
    fainted: Boolean = this.fainted,
    actionConstraints: BattlePokemonActionConstraintView = this.actionConstraints,
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
)
