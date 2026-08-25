package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.mechanics.RecursiveControlEffect

/**
 * One projected outcome of a complete turn.
 *
 * Separated from the projector that produces it because the recursive history also consumes it. While
 * the type lived inside the projector, the history had to reach into the projector and the projector
 * had to reach back for the history's own types - a two-file cycle created purely by where a data
 * class happened to be declared.
 */
internal data class PublicTurnProjection(
    val state: BattleStateView,
    val order: List<BattleSide>,
    val actionOrderPokemonIds: List<UUID> = emptyList(),
    val probability: Double = 1.0,
    val executedSides: Set<BattleSide> = emptySet(),
    val controlEffects: List<RecursiveControlEffect> = emptyList(),
    val switchedSides: Set<BattleSide> = emptySet(),
    val executedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
    val badPoisonTurnsByPokemon: Map<UUID, Int> = emptyMap(),
    val expectedScoreAdjustment: Double = 0.0,
    val protectionResultsByPokemon: Map<UUID, Boolean> = emptyMap(),
)
