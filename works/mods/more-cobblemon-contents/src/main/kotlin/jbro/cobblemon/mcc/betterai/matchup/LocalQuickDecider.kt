package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Random

/**
 * The searchless singles decision on top of the one-turn ranking, read from [MatchupScores]. The order follows
 * pokeemerald-expansion's trainer AI (a sure knockout first, then a switch only for a reason and only into a
 * Pokemon that takes the hit and wins), with PokeRogue's margin a switch must clear, raised after recent switches.
 *
 * 1. Knockout: an attack that knocks the opponent out this turn before it is knocked out itself is taken.
 * 2. A reason to leave: no attack does damage, the one-on-one is lost, the attacking stat is at -2 or lower, or a
 *    draining effect (Leech Seed, Curse, Perish Song, Yawn) sits on it. Without one, a switch the ranking chose
 *    stands only on a clear gain ([CLEAR_GAIN]).
 * 3. Where to: a switch whose Pokemon survives the predicted hit on entry ([SURVIVAL]), mostly survives the opponent's
 *    most damaging move too ([WORST_SURVIVAL]), and scores the margin over staying.
 *    The margin grows when the last switch was this turn or the one before, so the AI does not ping-pong.
 *    Below Boss a share of the reasons is ignored on purpose ([stayRoll]), as the Emerald AI does, so a player
 *    cannot read every switch.
 * 4. A move kept: an attack doing well under the best one's damage gives way to it, and a heal at nearly full HP
 *    or entry hazards against a last Pokemon give way to the best attack.
 *
 * Returns null to keep the ranking's choice.
 */
internal object LocalQuickDecider {
    class Verdict(val rank: LocalBattleActionRank, val reason: String)

    fun decide(
        ranked: List<LocalBattleActionRank>,
        chosen: LocalBattleActionRank,
        context: BattleDecisionContext,
        scores: MatchupScores,
        tier: BattleTrainerTier,
        excluded: Set<String>,
        seed: Long,
    ): Verdict? {
        val state = context.state
        val self = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val foe = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val open = ranked.filter { it.outcome.candidate.actionId !in excluded }
        val moves = open.filter { part(it).kind == BattleActionKind.USE_MOVE }
        val switches = open.filter { part(it).kind == BattleActionKind.SWITCH && part(it).switchPokemonId != null }
        val stay = scores.pokemon(self.battlePokemonId, foe.battlePokemonId) ?: return null
        val moveScores = scores.moves(self.battlePokemonId, foe.battlePokemonId).associateBy { PublicIds.canonical(it.moveId) }
        fun damage(rank: LocalBattleActionRank): MoveMatchupScore? =
            part(rank).moveId?.let { moveScores[PublicIds.canonical(it)] }?.takeIf { it.maximumDamageFraction > 0.0 }
        val attacks = moves.mapNotNull { rank -> damage(rank)?.let { rank to it } }
        val theirKnockout = stay.opponentMove?.knockoutChanceWithin(1) ?: 0.0

        // 1. A knockout this turn, landed before their hit or survived.
        attacks.map { (rank, score) ->
            val first = if (LocalPublicTurnOrder.effectivePriority(state, BattleSide.ALLY, part(rank)) > 0) 1.0
                else stay.subjectMovesFirstProbability
            rank to score.knockoutChanceWithin(1) * (first + (1.0 - first) * (1.0 - theirKnockout))
        }.maxByOrNull { it.second }?.takeIf { it.second >= KNOCKOUT }?.let { (rank, _) ->
            return Verdict(rank, "knockout")
        }

        // 2. A reason to leave.
        val reasons = buildList {
            // Nothing to hit with, and no status to land either: a status move on a foe that already has one is no use.
            if (attacks.isEmpty() && (foe.statusId != null || moves.none { inflictsStatus(part(it)) })) add("no_damage")
            if (cannotAct(self)) add("cannot_act")
            if (stay.winProbability < LOSING) add("loses_one_on_one")
            if (attackStage(self) <= -2) add("attack_dropped")
            if (self.knownVolatileEffectIds.any { PublicIds.canonical(it) in DRAINING }) add("draining_effect")
        }
        val random = Random(seed xor 0x5157_1C4E_D3C1_DE11L)
        val reason = reasons.firstOrNull()?.takeUnless { it !in UNCONDITIONAL && random.nextDouble() < stayRoll(tier) }

        // 3. Where to: survives the entry and clears the margin over staying.
        // A replacement after a knockout is no switch the AI chose, so it does not count as a recent one.
        val replaced = state.observedEvents.any { event ->
            event.kind == BattleObservedEventKind.FAINTED && event.turn >= state.turn - 1 &&
                state.pokemon.any { it.side == BattleSide.ALLY && it.battlePokemonId == event.actorPokemonId }
        }
        val margin = MARGIN + if (replaced) 0.0 else when (context.memory.turnsSinceLastSwitch) {
            0 -> RECENT_SWITCH * 2
            1 -> RECENT_SWITCH
            else -> 0.0
        }
        fun entry(rank: LocalBattleActionRank) = scores.switchIn(requireNotNull(part(rank).switchPokemonId), foe.battlePokemonId, self.battlePokemonId)
        // A Pokemon that can do nothing gives way to any one that hits back, even one the hit on entry may knock out:
        // staying loses for certain (the Emerald AI's "all moves bad" switch).
        val pool = if (reason == "no_damage") ranked.filter { part(it).kind == BattleActionKind.SWITCH && part(it).switchPokemonId != null }
            else switches
        val viable = pool.mapNotNull { rank ->
            val entry = entry(rank) ?: return@mapNotNull null
            // My switch against each of their moves: the predicted one must be survived, and the worst one mostly.
            if (reason != "no_damage" && (entry.predictedSurvival < SURVIVAL || entry.worstSurvival < WORST_SURVIVAL)) return@mapNotNull null
            // Leaving a Pokemon that does nothing is worth it for any one that hits back.
            if (reason == "no_damage" && (entry.afterEntry?.subjectMove?.maximumDamageFraction ?: 0.0) <= 0.0) return@mapNotNull null
            // Ranked as the Emerald AI ranks switch-ins: one that also hits the opponent hard comes first.
            rank to entry.score + OFFENSE * (entry.afterEntry?.subjectMove?.score ?: 0.0)
        }.sortedByDescending { it.second }
        val best = viable.firstOrNull()
        fun gain(rank: LocalBattleActionRank) = entry(rank)!!.score - stay.score
        if (reason != null && best != null && (reason in UNCONDITIONAL || gain(best.first) >= margin)) {
            return Verdict(best.first, "switch_$reason")
        }
        if (part(chosen).kind == BattleActionKind.SWITCH) {
            val gain = viable.firstOrNull { it.first === chosen }?.let { gain(it.first) }
            // A reason the roll let pass still lets the ranking's own switch stand; without one it must be clear.
            if (gain != null && gain >= (if (reasons.isNotEmpty()) margin else CLEAR_GAIN + margin - MARGIN)) return null
            // A switch without a reason, or into a Pokemon that does not take the hit: stay and attack.
            return bestAttack(attacks)?.let { Verdict(it, "stay_no_reason") }
        }

        // 4. The move kept.
        val chosenMove = part(chosen)
        if (chosenMove.kind != BattleActionKind.USE_MOVE) return null
        val strongest = bestAttack(attacks) ?: return null
        val chosenDamage = damage(chosen)
        if (chosenDamage != null) {
            val top = damage(strongest)!!.score
            return if (chosenDamage.score < WEAK_ATTACK * top) Verdict(strongest, "weak_attack") else null
        }
        if (wasted(chosenMove, self, context)) return Verdict(strongest, "wasted_status")
        return null
    }

    private fun bestAttack(attacks: List<Pair<LocalBattleActionRank, MoveMatchupScore>>): LocalBattleActionRank? =
        attacks.maxByOrNull { it.second.score }?.first

    /** A heal at nearly full HP, or entry hazards when the opponent has nothing left to switch in. */
    private fun wasted(move: BattleActionCandidate, self: BattlePokemonStateView, context: BattleDecisionContext): Boolean {
        val details = move.moveDetails ?: return false
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return false
        val effects = details.effects?.effects.orEmpty()
        val heals = effects.any { it.kind == BattleMoveEffectKind.HEAL_FRACTION }
        if (heals && self.hpFraction >= FULL_HP) return true
        val hazard = PublicIds.canonical(move.moveId.orEmpty()) in HAZARDS
        return hazard && (context.state.remainingPokemonBySide[BattleSide.OPPONENT] ?: 6) <= 1
    }

    private fun inflictsStatus(move: BattleActionCandidate): Boolean =
        move.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
            move.moveDetails.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.STATUS }

    private fun cannotAct(pokemon: BattlePokemonStateView): Boolean {
        val status = PublicIds.canonical(pokemon.statusId ?: return false)
        return "frz" in status || "frozen" in status || "slp" in status || "sleep" in status
    }

    private fun attackStage(pokemon: BattlePokemonStateView): Int {
        val stats = pokemon.combatStats
        val physical = (stats?.attack?.maximum ?: 0) >= (stats?.specialAttack?.maximum ?: 0)
        return pokemon.statStages[if (physical) "attack" else "special_attack"] ?: 0
    }

    /** The share of reasons to switch a tier lets pass, so its switches are not all predictable. */
    private fun stayRoll(tier: BattleTrainerTier): Double = when (tier) {
        BattleTrainerTier.BOSS -> 0.1
        BattleTrainerTier.ADVANCED -> 0.25
        else -> 0.4
    }

    private fun part(rank: LocalBattleActionRank): BattleActionCandidate = rank.outcome.candidate.let {
        if (it.kind == BattleActionKind.COMPOSITE) it.componentActions.first() else it
    }

    /** Reasons no roll lets pass and no margin holds back: staying does nothing at all. */
    private val UNCONDITIONAL = setOf("no_damage", "cannot_act")
    private val DRAINING = setOf("leechseed", "curse", "perish1", "perish2", "perish3", "perishsong", "yawn")
    private val HAZARDS = setOf("stealthrock", "spikes", "toxicspikes", "stickyweb")

    /** Knockout chance (landed before their hit, or survived) at which the attack is taken outright. */
    const val KNOCKOUT = 0.8
    /** Win chance in the one-on-one below which staying is a reason to leave. */
    const val LOSING = 0.3
    /** Survival of the hit on entry a switch needs. */
    const val SURVIVAL = 0.5
    /** Survival of the opponent's most damaging move on entry a switch needs. */
    const val WORST_SURVIVAL = 0.3
    /** Matchup score (-1..1) a switch must gain over staying. */
    const val MARGIN = 0.3
    /** Added to [MARGIN] per recent switch. */
    const val RECENT_SWITCH = 0.2
    /** A switch with no reason must gain this much to stand. */
    const val CLEAR_GAIN = 0.7
    /** An attack under this share of the best attack's score gives way to it. */
    const val WEAK_ATTACK = 0.6
    /** Weight of a switch-in's own best attack (1 / hits to knock the opponent out) in ranking switch-ins. */
    const val OFFENSE = 1.0
    const val FULL_HP = 0.9
}
