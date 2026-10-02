package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * Multi-turn control a move imposes on a Pokemon: charging, recharging, taunt, encore, trapping.
 *
 * Its own file because both the turn projection that reports these and the history that accumulates
 * them need the type. Declaring it beside one of the two consumers is what kept those two files
 * mutually dependent after everything else had been separated.
 */
internal enum class RecursiveControlEffectKind { CHARGE, RECHARGE, DELAYED_STRIKE, TAUNT, ENCORE, TRAP, SALT_CURE, LEECH_SEED, GHOST_CURSE, PARTIAL_TRAP, FLINCH, WISH, LOCKED_MOVE, DESTINY_BOND, YAWN, CONFUSION, ENDURE }

internal data class RecursiveDelayedStrike(
    val sourcePokemon: BattlePokemonStateView,
    val sourceSide: BattleSide,
    val targetSide: BattleSide,
    val targetSlot: Int,
    val moveId: String,
    val moveDetails: BattleMoveCandidateView,
    val remainingTurns: Int,
)

internal data class RecursiveControlEffect(
    val kind: RecursiveControlEffectKind,
    val sourceSide: BattleSide,
    val sourcePokemonId: UUID,
    val targetPokemonId: UUID,
    val valueId: String? = null,
    val delayedStrike: RecursiveDelayedStrike? = null,
)
