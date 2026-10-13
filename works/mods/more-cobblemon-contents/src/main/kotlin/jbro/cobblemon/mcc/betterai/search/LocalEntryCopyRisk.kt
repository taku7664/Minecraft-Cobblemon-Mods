package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The chance a stat raise is handed to the opponent: one of its waiting Pokemon, sent in, turns into the raised
 * Pokemon and takes its stages. Read from the engine on this board ([jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker.entryCopyShares]):
 * each waiting Pokemon, the seen ones with the abilities still possible for them and the preview-only ones with their
 * species' own, all alike, is dragged in once per ability, and whether it transformed is the engine's (Imposter). A
 * seen one that has revealed Transform copies by its move. No ability or species is named (2026-10-13, decided with
 * the user); Transform is found by its own id, as Showdown finds it.
 */
internal object LocalEntryCopyRisk {
    fun chance(context: BattleDecisionContext, tier: BattleTrainerTier): Double {
        val state = context.state
        if (state.format != BattleFormat.SINGLE) return 0.0
        val foe = state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return 0.0
        if ((state.remainingPokemonBySide[BattleSide.OPPONENT] ?: 0) <= 1) return 0.0
        val seenWaiting = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
        val transformer = seenWaiting.any { pokemon -> pokemon.knownMoveIds.any { PublicIds.canonical(it) == TRANSFORM } }
        if (transformer) return 1.0
        val known = seenWaiting.associate { it.battlePokemonId.toString() to LocalEntryAbilityProjector.publicPossibleAbilities(state, it) }
            .filterValues { it.isNotEmpty() }
        val root = LocalEntryAbilityCheck.root(context, tier) ?: return 0.0
        val shares = LocalEngineStopProbe.lease.withWorker(LocalEntryAbilityCheck.deadline(context)) { worker ->
            worker.entryCopyShares(LocalEntryAbilityCheck.root(worker, context, root).snapshotJson, foe.battlePokemonId.toString(), known)
        } ?: return 0.0
        return 1.0 - shares.fold(1.0) { kept, share -> kept * (1.0 - share) }
    }

    private const val TRANSFORM = "transform"
}
