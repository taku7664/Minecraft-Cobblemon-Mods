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
    /**
     * How likely this action order is, given what is public about both sides' Speed.
     *
     * Separate from [probability], which is the chance of this outcome *within* the order. An order
     * the public ranges cannot resolve used to be one of an equally weighted set, so a Speed drop that
     * narrowed the gap without closing it read exactly like one that did nothing - the AI could not
     * tell a one-stage drop from a two-stage one once either left the order merely uncertain.
     */
    val orderProbability: Double = 1.0,
    val executedSides: Set<BattleSide> = emptySet(),
    val controlEffects: List<RecursiveControlEffect> = emptyList(),
    val switchedSides: Set<BattleSide> = emptySet(),
    val executedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
    val badPoisonTurnsByPokemon: Map<UUID, Int> = emptyMap(),
    val expectedScoreAdjustment: Double = 0.0,
    val protectionResultsByPokemon: Map<UUID, Boolean> = emptyMap(),
)
