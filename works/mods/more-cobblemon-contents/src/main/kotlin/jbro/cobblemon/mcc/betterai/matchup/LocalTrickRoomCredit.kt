package jbro.cobblemon.mcc.betterai.matchup

import kotlin.math.abs
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicFieldMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.simulation.NativeInBattleFormes
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Trick Room, in the root's units: how much turning the speed order over changes this side's matchups for the turns it
 * lasts, across both teams.
 *
 * Every living ally against every opponent of the selection: a seen one by the pair table read with Trick Room toggled
 * ([MatchupSpeedField.TRICK_ROOM_TOGGLED], win chance after minus before), one still to come from the team preview by
 * speed alone (a slower ally gains the order, a faster one loses it, an overlapping one neither), each such flip worth
 * the seen pairs' mean change where their order flipped. The mean change, times [LocalDecisionTuning.knockoutMaterialScore]
 * and [ROOM_EXCHANGES], is the move's value. Already up, the move ends the room and the same reading comes out negative.
 *
 * It took the generic status value and the engine's two-turn reading: 6 points in front of a faster Garchomp with a slow
 * Azumarill behind, 258 where the engine saw one knockout come first (2026-10-13, G-926). The room is found by its
 * field-condition id in the move data: one id, named (hard-coded), as [LocalPublicFieldMechanics.trickRoomActive] does.
 */
internal object LocalTrickRoomCredit {
    /** Whether [candidate] toggles Trick Room; the engine's status reading leaves such a move alone. */
    fun prices(candidate: BattleActionCandidate): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE && candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
            candidate.moveDetails?.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.FIELD_CONDITION && it.valueId?.let(PublicIds::canonical) == TRICK_ROOM
            }

    /** The value of toggling Trick Room now, or null when no pair can be read. */
    fun value(context: BattleDecisionContext, scores: MatchupScores, tuning: LocalDecisionTuning): Double? {
        val state = context.state
        fun living(side: BattleSide) = state.pokemon.filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        val allies = living(BattleSide.ALLY)
        val opponents = living(BattleSide.OPPONENT)
        val changes = mutableListOf<Double>()
        val flips = mutableListOf<Double>()
        for (ally in allies) for (foe in opponents) {
            val now = scores.pokemon(ally.battlePokemonId, foe.battlePokemonId) ?: continue
            val toggled = scores.pokemon(ally.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.TRICK_ROOM_TOGGLED) ?: continue
            val change = toggled.winProbability - now.winProbability
            changes += change
            if (change != 0.0) flips += abs(change)
        }
        // The selection's Pokemon not seen yet, by the preview's speed against each ally's.
        val preview = context.opponentTeamPreview
        if (preview != null) {
            val seen = state.pokemon.filter { it.side == BattleSide.OPPONENT }
            val unseen = preview.pokemon.filter { slot -> seen.none { NativeInBattleFormes.samePublicIdentity(it, slot) } }
            val coming = (preview.selectionSize - seen.size).coerceIn(0, unseen.size)
            if (coming > 0) {
                val flip = flips.takeIf { it.isNotEmpty() }?.average() ?: UNSEEN_FLIP
                // Under a room already up, toggling it gives the order back.
                val direction = if (LocalPublicFieldMechanics.trickRoomActive(state)) -1.0 else 1.0
                for (ally in allies) {
                    val mine = LocalPublicTurnOrder.effectiveSpeed(state, ally)?.let { BattleIntegerRange(it.first, it.second) }
                    val order = unseen.map { slot -> order(mine, slot.combatStats?.speed) }.average()
                    repeat(coming) { changes += direction * flip * order }
                }
            }
        }
        if (changes.isEmpty()) return null
        return changes.average() * tuning.knockoutMaterialScore * ROOM_EXCHANGES
    }

    /**
     * The chance [candidate]'s user gets the room up this turn: at -7 every hit on the field lands first, and one that
     * knocks it out leaves the room down. Each foe's likeliest knockout, at the chance it moves and aims at the user (one
     * in as many of this side's Pokemon in front), two hits together counted ([LocalDynamaxGuard.knockout]). A Bronzong
     * at 6% in front of a Skarmory's Brave Bird took the room's 61 and fell before it moved (2026-10-19).
     */
    fun landing(candidate: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Double =
        LocalActingChance.of(candidate, context, scores)

    /** +1 when the ally is surely slower (the room hands it the first move), -1 when surely faster, 0 otherwise. */
    private fun order(mine: BattleIntegerRange?, theirs: BattleIntegerRange?): Double = when {
        mine == null || theirs == null -> 0.0
        mine.maximum < theirs.minimum -> 1.0
        mine.minimum > theirs.maximum -> -1.0
        else -> 0.0
    }

    private const val TRICK_ROOM = "trickroom"
    /** The exchanges the room's turns hold: it lasts this one and four more. */
    private const val ROOM_EXCHANGES = 2.0
    /** A flip's change in win chance when no seen pair shows one. */
    private const val UNSEEN_FLIP = 0.25
}
