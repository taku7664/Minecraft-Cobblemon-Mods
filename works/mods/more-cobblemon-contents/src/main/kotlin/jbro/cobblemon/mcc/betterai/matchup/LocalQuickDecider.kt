package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
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
 * Returns null to keep the ranking's choice. Only the [HARD] verdicts replace the drawn action; the brain adds the rest
 * to the ranking as a bonus before the draw, reading each candidate as the one chosen (2026-10-12).
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
        /** The ranking's credit per action for the switches it expects the opponent to make ([LocalSwitchPrediction]). */
        predicted: Map<String, Double> = emptyMap(),
        /** Per opposing Pokemon, the chance it switches to each one behind it. */
        expectedSwitches: Map<java.util.UUID, Map<java.util.UUID, Double>> = emptyMap(),
        /** Per debuff action, what it takes off its target's exchanges with this team ([LocalDebuffValue]). */
        debuffGains: Map<String, Double> = emptyMap(),
        /** The verdicts this call may return; a reason left out is passed over and the checks go on. */
        allow: (String) -> Boolean = { true },
        /** The board and pair table a stat raise is judged on: the Mega's when it Mega Evolves; null when none can be read. */
        setupView: (jbro.cobblemon.mcc.internal.ai.BattleActionCandidate) -> Pair<BattleDecisionContext, MatchupScores>? = { context to scores },
        /**
         * Per status move a rule found worth playing on this board (a trapping Perish Song, a Trick Room that turns the
         * order this team's way): staying to play one is not staying for nothing.
         */
        stayCredits: Map<String, Double> = emptyMap(),
        /** The opponent's predicted actions: a move that needs the target to attack (Sucker Punch) lands at that chance. */
        opponentIntents: List<OpponentIntent> = emptyList(),
    ): Verdict? {
        fun pick(rank: LocalBattleActionRank, reason: String): Verdict? = if (allow(reason)) Verdict(rank, reason) else null
        val state = context.state
        val foe = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val self = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return replacement(ranked, excluded, scores, foe)?.takeIf { allow(it.reason) }
        val open = ranked.filter { it.outcome.candidate.actionId !in excluded }
        val moves = open.filter { part(it).kind == BattleActionKind.USE_MOVE }
        val switches = open.filter { part(it).kind == BattleActionKind.SWITCH && part(it).switchPokemonId != null }
        val stay = scores.pokemon(self.battlePokemonId, foe.battlePokemonId) ?: return null
        val moveScores = scores.moves(self.battlePokemonId, foe.battlePokemonId).associateBy { PublicIds.canonical(it.moveId) }
        fun damage(rank: LocalBattleActionRank): MoveMatchupScore? =
            part(rank).moveId?.let { moveScores[PublicIds.canonical(it)] }?.takeIf { it.maximumDamageFraction > 0.0 }
        val attacks = moves.mapNotNull { rank -> damage(rank)?.let { rank to it } }
        val theirKnockout = stay.opponentMove?.knockoutChanceWithin(1) ?: 0.0

        // 1. A knockout this turn, landed before their hit or survived. Of several, the one the ranking put first: it
        // has weighed what each does if the opponent switches instead. The knockout only holds if the opponent stays, so
        // it is the choice as often as a stay is expected and points before the draw otherwise: no threshold for a
        // small change in the prediction to cross (2026-10-13, user decision). The roll is the decision's seed, so every
        // call within one decision reads it the same.
        // A knockout that can wait is not forced when this one is low and can heal: the foe cannot knock it out
        // (LocalPokemonWorth.urgency at its floor), so the ranking weighs the heal (at this Pokemon's worth) against the
        // knockout (at that urgency). A +4 Defence Corviknight at 23% Brave Birded a Mimikyu it would beat anyway, fell to
        // 15% on the recoil and lost to the next foe (dev server, 2026-10-20). A healthy one still takes it (2026-10-13).
        val canWaitToHeal = self.hpFraction <= HEAL_WAIT_HP &&
            jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth.urgency(foe.battlePokemonId) <= LocalKnockoutWorth.MINIMUM_URGENCY &&
            moves.any { rank -> part(rank).moveDetails?.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget.USER } }
        val switchShare = (expectedSwitches[foe.battlePokemonId]?.values?.sum() ?: 0.0).coerceIn(0.0, 1.0)
        val switchExpected = switchShare > 0.0 && Random(seed xor 0x6B0C_2F5A_91D3_47E1L).nextDouble() < switchShare
        // Each of the foe's moves that knocks this one out first, by its own priority against the attack's: a higher one
        // always, an equal one by Speed. Read from the exchange's move alone at Speed, a Dynamaxed Mimikyu went for Max
        // Starfall into a 7% Kingambit's Sucker Punch and fell first, where Shadow Sneak (+1 like it, Mimikyu faster) was a
        // sure knockout (hand-played Fantina, 2026-10-20).
        val foeMoves = scores.moves(foe.battlePokemonId, self.battlePokemonId)
        fun preempted(rank: LocalBattleActionRank): Double {
            val ours = LocalPublicTurnOrder.effectivePriority(state, BattleSide.ALLY, part(rank))
            val byExchange = (1.0 - stay.subjectMovesFirstProbability) * theirKnockout
            return foeMoves.maxOfOrNull { move ->
                val before = when {
                    move.priority > ours -> 1.0
                    move.priority < ours -> 0.0
                    else -> 1.0 - stay.subjectMovesFirstProbability
                }
                move.knockoutChanceWithin(1) * before
            }?.let { maxOf(it, if (ours > 0) 0.0 else byExchange) } ?: if (ours > 0) 0.0 else byExchange
        }
        attacks.firstOrNull { (rank, score) ->
            // Sucker Punch knocks out only if the foe attacks: a Swords Dance in front of it is a turn lost.
            val lands = if (LocalPendingAttackChance.requiresPendingAttack(part(rank))) LocalPendingAttackChance.attacks(foe, context, opponentIntents) else 1.0
            !canWaitToHeal && lands * score.knockoutChanceWithin(1) * (1.0 - preempted(rank) * LocalKnockoutWorth.actsThisTurn(foe, state)) >= KNOCKOUT
        }?.let { (rank, _) -> pick(rank, if (switchExpected) KNOCKOUT_IF_STAYS else "knockout") }?.let { return it }

        // 2. A reason to leave.
        val reasons = buildList {
            // Nothing to hit with, and no status to land either: a status move on a foe that already has one is no use.
            // A move a rule credits is something to do: Perish Song in front of a trapped Chansey was read as nothing and
            // the Mega Gengar switched out (2026-10-13).
            if (attacks.isEmpty() && moves.none { inflictsStatus(part(it), self, foe, state) } &&
                moves.none { (stayCredits[it.outcome.candidate.actionId] ?: 0.0) > 0.0 }) add("no_damage")
            if (cannotAct(self)) add("cannot_act")
            if (stay.winProbability < LOSING) add("loses_one_on_one")
            if (attackStage(self) <= -2) add("attack_dropped")
            // A count kept as `perishsong:<count>` reads as its family, not as the bare count canonical would leave.
            if (self.knownVolatileEffectIds.any { PublicIds.canonical(it.substringBefore(':')) in DRAINING }) add("draining_effect")
        }
        val random = Random(seed xor 0x5157_1C4E_D3C1_DE11L)
        val reason = reasons.firstOrNull()?.takeUnless { it !in UNCONDITIONAL && random.nextDouble() < stayRoll(tier) }

        // 3. Where to: survives the entry and clears the margin over staying.
        // A replacement after a knockout is no switch the AI chose, so it does not count as a recent one.
        val replaced = state.observedEvents.any { event ->
            event.kind == BattleObservedEventKind.FAINTED && event.turn >= state.turn - 1 &&
                state.pokemon.any { it.side == BattleSide.ALLY && it.battlePokemonId == event.actorPokemonId }
        }
        val keep = scores.preserves[self.battlePokemonId]?.score ?: 0.0
        val margin = MARGIN - (if (keep >= LocalSwitchRules.WORTH_KEEPING) KEEP_DISCOUNT else 0.0) +
            if (replaced) 0.0 else when (context.memory.turnsSinceLastSwitch) {
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
        // A switch-in that knocks the foe out sooner earns a little over the exchange score, which reads a won one-on-one
        // the same however long it takes: a burned Milotic winning in four turns against a Lucario winning in one
        // (gain 0.18, 2026-10-19). Small on purpose, it tips only a switch already near the margin.
        fun knockoutTurns(move: MoveMatchupScore?) = move?.let { m -> (1..KNOCKOUT_TURNS_READ).firstOrNull { m.knockoutChanceWithin(it) >= 0.5 } }
        fun sooner(rank: LocalBattleActionRank): Double {
            val switched = knockoutTurns(entry(rank)?.afterEntry?.subjectMove) ?: return 0.0
            val staying = knockoutTurns(stay.subjectMove) ?: (KNOCKOUT_TURNS_READ + 1)
            return (FASTER_KNOCKOUT_PER_TURN * (staying - switched)).coerceIn(0.0, FASTER_KNOCKOUT_MAX)
        }
        fun gain(rank: LocalBattleActionRank) = entry(rank)!!.score - stay.score + sooner(rank)
        // One the team can spare, knocked out this turn anyway, stays and hits when the hit on entry would cost the
        // next one much: its fall brings that one in without the hit. A healthy one, or one with a switch-in that
        // takes the hit well, is not given away.
        val sacking = reason == "loses_one_on_one" && keep <= LocalSwitchRules.WORTHLESS && attacks.isNotEmpty() &&
            theirKnockout >= LocalSwitchRules.DOOMED && (best == null || best.first.let { rank ->
                val free = scores.pokemon(requireNotNull(part(rank).switchPokemonId), foe.battlePokemonId)?.score ?: return@let false
                free - entry(rank)!!.score >= LocalSwitchRules.FREE_ENTRY_MARGIN
            })
        if (!sacking && reason != null && best != null && (reason in UNCONDITIONAL || gain(best.first) >= margin)) {
            // An attack that switches its user out (U-turn, Volt Switch, Flip Turn) leaves too, hitting on the way: where
            // the ranking puts one at or above the best switch, it is the way out. Given to the switch, a faster
            // Pelipper's U-turn (ranked first) lost the draw to a hard switch into Barraskewda (hand-played Nessa,
            // 2026-10-20).
            val pivot = if (reason == "cannot_act") null else attacks.map { it.first }
                .filter { rank -> part(rank).moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.SWITCH_USER } }
                .minByOrNull { ranked.indexOf(it) }
            val leave = pivot?.takeIf { ranked.indexOf(it) < ranked.indexOf(best.first) } ?: best.first
            pick(leave, "switch_$reason")?.let { return it }
        }
        if (part(chosen).kind == BattleActionKind.SWITCH) {
            if (sacking) return bestAttack(attacks)?.let { pick(it, "sack") }
            val gain = viable.firstOrNull { it.first === chosen }?.let { gain(it.first) }
            // A reason the roll let pass still lets the ranking's own switch stand; without one it must be clear.
            if (gain != null && gain >= (if (reasons.isNotEmpty()) margin else CLEAR_GAIN + margin - MARGIN)) return null
            // A switch without a reason, or into a Pokemon that does not take the hit: stay and attack.
            return bestAttack(attacks)?.let { pick(it, "stay_no_reason") }
        }

        // 4. The move kept.
        val chosenMove = part(chosen)
        if (chosenMove.kind != BattleActionKind.USE_MOVE) return null
        // The scores read the base move. Terastallization, a Z-Move or Dynamax changes what the move does, so a move
        // chosen with one is the ranking's to keep; Mega Evolution leaves the move as it is.
        if (transformsMove(chosenMove)) return null
        val strongest = bestAttack(attacks) ?: return null
        val statusScores = scores.statusMoves(self.battlePokemonId, foe.battlePokemonId).associateBy { PublicIds.canonical(it.moveId) }
        fun statusScore(rank: LocalBattleActionRank) = part(rank).moveId?.let { statusScores[PublicIds.canonical(it)] }
        // Their hit as the exchange reads it. It is capped at the HP left, so a capped hit is known only to be at least that.
        val theirs = stay.opponentMove
        val theirHit = theirs?.let { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy } ?: 0.0
        val hitCapped = theirs != null && theirs.maximumDamageFraction >= self.hpFraction - 1e-6
        // A faster foe whose hit all but surely knocks it out lands before any heal: a Gholdengo at 18% chose Recover
        // in front of a faster Landorus that had shown Earth Power (league mock battle, 2026-10-13).
        val faintsFirst = theirs != null && stay.subjectMovesFirstProbability < 0.5 &&
            theirs.knockoutChanceWithin(1) * theirs.accuracy >= FAINTS_FIRST
        fun futile(heal: Double) = faintsFirst || (hitCapped && self.hpFraction >= heal) || (!hitCapped && theirHit >= heal * FUTILE_HEAL)
        val chosenHeal = healShare(chosenMove)
        if (chosenHeal > 0.0) {
            // The Emerald AI heals under half HP, or two thirds when it moves first, and not into a hit that undoes it.
            val healBelow = if (stay.subjectMovesFirstProbability >= 0.5) HEAL_BELOW_FIRST else HEAL_BELOW
            if (self.hpFraction >= healBelow) pick(strongest, "early_heal")?.let { return it }
            if (futile(chosenHeal)) return pick(strongest, "futile_heal")
            return null
        }
        val chosenDamage = damage(chosen)
        if (chosenDamage != null) {
            // Low, losing, and a heal that outpaces their hit: heal, unless it has been healing turn after turn.
            val heal = moves.mapNotNull { rank -> healShare(part(rank)).takeIf { it > 0.0 }?.let { rank to it } }.maxByOrNull { it.second }
            val healing = context.memory.lastMoveId?.let { last -> heal?.first?.let { PublicIds.canonical(part(it).moveId.orEmpty()) == PublicIds.canonical(last) } } == true &&
                context.memory.sameMoveRepeatCount >= HEAL_STREAK
            if (heal != null && !healing && self.hpFraction <= HEAL_AT && stay.winProbability < 0.5 && !futile(heal.second)) {
                pick(heal.first, "heal_in_time")?.let { return it }
            }
            // Not while it can be finished: low, or already in knockout range.
            val finishing = foe.hpFraction <= FINISH_HP || attacks.any { it.second.knockoutChanceWithin(1) >= FINISH_CHANCE }
            if (!finishing) freeTurn(moves, self, foe, state, scores, expectedSwitches, theirKnockout)?.takeIf { allow(it.reason) }?.let { return it }
            // A status move that turns the exchange (a burn on a physical attacker, paralysis on a faster one).
            moves.mapNotNull { rank -> statusScore(rank)?.let { rank to it } }.maxByOrNull { it.second.score }
                ?.takeIf { it.second.score >= STATUS_GAIN }?.let { pick(it.first, "status_better") }?.let { return it }
            val top = damage(strongest)!!.score
            // An attack the ranking chose for the Pokemon it expects to come in is not weak: it was aimed past this one.
            val aimedAtSwitch = (predicted[chosen.outcome.candidate.actionId] ?: 0.0) > (predicted[strongest.outcome.candidate.actionId] ?: 0.0)
            // Nor one that frees the turn with a certain flinch (Fake Out): it is chosen for the turn, not the damage
            // (LocalFreeTurnCredit).
            val freesTurn = jbro.cobblemon.mcc.betterai.evaluation.LocalRootFlinchValue.certain(chosenMove) &&
                jbro.cobblemon.mcc.betterai.evaluation.LocalRootFlinchValue.chance(chosenMove, self, foe, state) > 0.0 &&
                jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(chosenMove, context) <= 0.0
            return if (!aimedAtSwitch && !freesTurn && chosenDamage.score < WEAK_ATTACK * top) pick(strongest, "weak_attack") else null
        }
        if (wasted(chosenMove, context)) pick(strongest, "wasted_status")?.let { return it }
        // A stat raise is judged as LocalSetupGate.score judges it: whether its user still wins the one-on-one after it,
        // the setup turn's hit paid. Not whether it raises the one-on-one: one already won cannot rise, and a boost
        // that leaves it won is a free one.
        if (LocalSetupGate.raisesOwnStats(chosen.outcome.candidate)) {
            val win = setupView(chosen.outcome.candidate)?.let { (view, table) ->
                LocalSetupGate.score(chosen.outcome.candidate, view, table, knockoutMaterialScore = 1.0)
            }?.win
            return if (win != null && win < SETUP_WIN) pick(strongest, "unsafe_setup") else null
        }
        // Only a debuff (a status condition or a stat drop on the target: Will-O-Wisp, Metal Sound) gives way to the hit:
        // when it takes nothing off the target's exchanges with this team (LocalDebuffValue), or the hit finishes the
        // target within two uses so the debuff has no time to matter (2026-10-12). Without a sweep reading, the
        // one-on-one exchange score decides as before.
        if (LocalDebuffValue.isDebuff(chosenMove)) {
            val finishedSoon = (damage(strongest)?.knockoutChanceWithin(2) ?: 0.0) >= STATUS_WORSE_KNOCKOUT
            val gain = debuffGains[chosen.outcome.candidate.actionId] ?: statusScore(chosen)?.score
            if (finishedSoon || (gain != null && gain <= 0.0)) return pick(strongest, "status_worse")
        }
        return null
    }

    /**
     * A turn the opponent is expected to spend switching (the Emerald AI's free turn): entry hazards while three or
     * more of its Pokemon are left and that hazard is not up, else a stat raise when the one expected to come in loses
     * to the raiser. Only when a stay does not cost the user: their hit, if they do not switch, is no knockout.
     */
    private fun freeTurn(
        moves: List<LocalBattleActionRank>,
        self: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        state: jbro.cobblemon.mcc.internal.ai.BattleStateView,
        scores: MatchupScores,
        expectedSwitches: Map<java.util.UUID, Map<java.util.UUID, Double>>,
        theirKnockout: Double,
    ): Verdict? {
        val switches = expectedSwitches[foe.battlePokemonId] ?: return null
        if (switches.values.sum() < FREE_TURN || theirKnockout >= FREE_TURN_RISK) return null
        val up = state.field.sideConditions[BattleSide.OPPONENT].orEmpty().mapTo(hashSetOf()) { PublicIds.canonical(it.effectId) }
        if ((state.remainingPokemonBySide[BattleSide.OPPONENT] ?: 0) >= HAZARD_TARGETS) {
            moves.firstOrNull { rank ->
                val id = PublicIds.canonical(part(rank).moveId.orEmpty())
                id in HAZARDS && id !in up && !transformsMove(part(rank)) && hazardTargets(id, state) >= HAZARD_HIT
            }?.let { return Verdict(it, "free_turn_hazard") }
        }
        val incoming = switches.maxByOrNull { it.value }?.key ?: return null
        val afterSwitch = scores.pokemon(self.battlePokemonId, incoming) ?: return null
        if (afterSwitch.winProbability < 0.5 || self.hpFraction < FREE_TURN_SETUP_HP) return null
        return moves.firstOrNull { !transformsMove(part(it)) && LocalSetupGate.raisesFor(part(it), state, self) }
            ?.let { Verdict(it, "free_turn_setup") }
    }

    /**
     * The opposing Pokemon still to come that [hazard] would touch: Toxic Spikes not Steel, Poison or Flying ones, Spikes
     * and Sticky Web not Flying ones, Stealth Rock all. One not seen yet is counted as touched.
     */
    private fun hazardTargets(hazard: String, state: jbro.cobblemon.mcc.internal.ai.BattleStateView): Int {
        val immune = when (hazard) {
            "toxicspikes" -> setOf("steel", "poison", "flying")
            "spikes", "stickyweb" -> setOf("flying")
            else -> emptySet()
        }
        val seen = state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        val unseen = ((state.remainingPokemonBySide[BattleSide.OPPONENT] ?: seen.size) - seen.size).coerceAtLeast(0)
        return unseen + seen.count { pokemon -> pokemon.knownTypeIds.none { PublicIds.canonical(it) in immune } }
    }

    /**
     * After a knockout: one that knocks the opponent out before it moves (the Emerald AI's revenge killer), else the best
     * one-on-one, its own attack counted as when switching in.
     */
    private fun replacement(
        ranked: List<LocalBattleActionRank>,
        excluded: Set<String>,
        scores: MatchupScores,
        foe: BattlePokemonStateView,
    ): Verdict? {
        val read = ranked.filter { it.outcome.candidate.actionId !in excluded && part(it).kind == BattleActionKind.SWITCH }
            .mapNotNull { rank -> part(rank).switchPokemonId?.let { scores.pokemon(it, foe.battlePokemonId) }?.let { rank to it } }
        if (read.size < 2) return null
        read.filter { (_, matchup) -> (matchup.subjectMove?.knockoutChanceWithin(1) ?: 0.0) * matchup.subjectMovesFirstProbability >= KNOCKOUT }
            .maxByOrNull { it.second.score }?.let { return Verdict(it.first, "replace_revenge") }
        return Verdict(read.maxBy { (_, matchup) -> matchup.score + OFFENSE * (matchup.subjectMove?.score ?: 0.0) }.first, "replace_matchup")
    }

    private fun transformsMove(move: BattleActionCandidate): Boolean =
        move.mechanic?.let { PublicIds.canonical(it.mechanicId) !in MEGA_IDS } ?: false

    /** The share of maximum HP a move heals its user, Rest (which also puts it to sleep) aside. */
    private fun healShare(move: BattleActionCandidate): Double {
        val effects = move.moveDetails?.effects?.effects.orEmpty()
        if (effects.any { it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.USER }) return 0.0
        return effects.filter { it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER && (it.probability ?: 1.0) >= 1.0 }
            .maxOfOrNull { effect -> effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0 } ?: 0.0
    }

    /**
     * The lower tiers' checks: only the senseless choices, each replaced by the ranking's own next pick so the tier
     * keeps its character. A move that publicly fails, an attack that does nothing when another does damage, a heal at
     * nearly full HP, hazards against a last Pokemon or already up, and staying in with nothing that does damage.
     */
    fun blunders(
        ranked: List<LocalBattleActionRank>,
        chosen: LocalBattleActionRank,
        context: BattleDecisionContext,
        scores: MatchupScores,
    ): Verdict? {
        val state = context.state
        val foe = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val self = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val move = part(chosen)
        if (move.kind != BattleActionKind.USE_MOVE || transformsMove(move)) return null
        val moveScores = scores.moves(self.battlePokemonId, foe.battlePokemonId).associateBy { PublicIds.canonical(it.moveId) }
        fun damages(rank: LocalBattleActionRank) = part(rank).kind == BattleActionKind.USE_MOVE &&
            (part(rank).moveId?.let { moveScores[PublicIds.canonical(it)] }?.maximumDamageFraction ?: 0.0) > 0.0
        // The ranking's next pick that does damage and does not fail.
        val next = ranked.firstOrNull { it !== chosen && damages(it) && !LocalPublicFailureTriage.fails(it.outcome.candidate, context) }
        val reason = when {
            LocalPublicFailureTriage.fails(move, context) -> "blunder_fails"
            // Only an attack read as doing nothing (an immunity); one the scores cannot read (Mirror Coat) is left alone.
            move.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS ->
                if (move.moveId?.let { moveScores[PublicIds.canonical(it)] }?.let { it.maximumDamageFraction <= 0.0 } == true) "blunder_no_effect" else null
            healShare(move) > 0.0 && self.hpFraction >= FULL_HP -> "blunder_full_heal"
            PublicIds.canonical(move.moveId.orEmpty()) in HAZARDS && (wasted(move, context) ||
                PublicIds.canonical(move.moveId.orEmpty()) in NON_STACKING &&
                state.field.sideConditions[BattleSide.OPPONENT].orEmpty().any { PublicIds.canonical(it.effectId) == PublicIds.canonical(move.moveId.orEmpty()) }) ->
                "blunder_hazard"
            else -> null
        } ?: return null
        next?.let { return Verdict(it, reason) }
        // Nothing does damage: hand over to one that does (the Emerald AI's "all moves bad" switch).
        return ranked.filter { part(it).kind == BattleActionKind.SWITCH && part(it).switchPokemonId != null }
            .mapNotNull { rank ->
                scores.switchIn(requireNotNull(part(rank).switchPokemonId), foe.battlePokemonId, self.battlePokemonId)
                    ?.takeIf { (it.afterEntry?.subjectMove?.maximumDamageFraction ?: 0.0) > 0.0 }?.let { rank to it.score }
            }.maxByOrNull { it.second }?.let { Verdict(it.first, "blunder_no_damage_switch") }
    }

    private fun bestAttack(attacks: List<Pair<LocalBattleActionRank, MoveMatchupScore>>): LocalBattleActionRank? =
        attacks.maxByOrNull { it.second.score }?.first

    /** Entry hazards when the opponent has nothing left to switch in. */
    private fun wasted(move: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        val details = move.moveDetails ?: return false
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return false
        val hazard = PublicIds.canonical(move.moveId.orEmpty()) in HAZARDS
        return hazard && (context.state.remainingPokemonBySide[BattleSide.OPPONENT] ?: 6) <= 1
    }

    /**
     * A status move that can still put a major status on [foe]: one aimed at it, which it can take. Read as any move
     * declaring a status, Rest (its own user's sleep) counted as one, and a Dubwool with nothing to hit an Aegislash with
     * stayed to Rest and Sleep Talk at 70% (dev server, 2026-10-20).
     */
    private fun inflictsStatus(move: BattleActionCandidate, self: BattlePokemonStateView, foe: BattlePokemonStateView,
        state: jbro.cobblemon.mcc.internal.ai.BattleStateView): Boolean =
        move.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
            move.moveDetails.effects?.effects.orEmpty().any { effect ->
                effect.kind == BattleMoveEffectKind.STATUS && effect.target == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget.SELECTED_TARGET &&
                    !jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(state, foe, effect.valueId, self)
            }

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

    /** How sure a faster foe's knockout must be for a heal to come too late. */
    private const val FAINTS_FIRST = 0.9

    /**
     * The verdicts that replace the drawn action outright: a sure knockout this turn, the replacement after a knockout,
     * and the senseless choices (staying in with nothing that does anything, a heal the hit undoes, hazards with no one
     * left to touch). The rest are preferences the brain adds to the ranking as a bonus (2026-10-12).
     */
    /**
     * The sure knockout on the share of decisions the predicted switch takes (the decision's roll under the predicted
     * switch chance): points before the draw, asked for on its own so the other verdicts are still read.
     */
    const val KNOCKOUT_IF_STAYS = "knockout_if_stays"

    val HARD = setOf("knockout", "replace_revenge", "replace_matchup", "switch_no_damage", "switch_cannot_act", "futile_heal", "wasted_status")

    /**
     * The preference verdicts that read the chosen action as the mistake (a weak attack, a switch without a reason, a
     * heal too early, a stat raise it does not survive, a debuff that does nothing): the points come off that action.
     * The others point to an action worth taking whatever was chosen, and the points go to it.
     */
    val AGAINST_CHOSEN = setOf("stay_no_reason", "sack", "weak_attack", "early_heal", "unsafe_setup", "status_worse")

    /** Reasons no roll lets pass and no margin holds back: staying does nothing at all. */
    private val UNCONDITIONAL = setOf("no_damage", "cannot_act")
    private val DRAINING = setOf("leechseed", "curse", "perish1", "perish2", "perish3", "perishsong", "yawn")
    private val MEGA_IDS = setOf("mega", "megaevolution")
    /** Hazards one use of which is all there is. */
    private val NON_STACKING = setOf("stealthrock", "stickyweb")
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

    /** Turns read for how soon a Pokemon knocks the foe out, and the switch gain each turn sooner earns, up to its cap. */
    private const val KNOCKOUT_TURNS_READ = 6
    private const val FASTER_KNOCKOUT_PER_TURN = 0.05
    private const val FASTER_KNOCKOUT_MAX = 0.2
    /** An attack under this share of the best attack's score gives way to it. */
    const val WEAK_ATTACK = 0.6
    /** HP at which a heal is a blunder at any tier. */
    const val FULL_HP = 0.9
    /** Weight of a switch-in's own best attack (1 / hits to knock the opponent out) in ranking switch-ins. */
    const val OFFENSE = 1.0
    /** A Pokemon worth keeping ([LocalSwitchRules.WORTH_KEEPING]) leaves on this much less margin. */
    const val KEEP_DISCOUNT = 0.15
    /** HP under which a heal is used, moving second / first. */
    const val HEAL_BELOW = 0.5
    const val HEAL_BELOW_FIRST = 0.66
    /** HP at which a losing Pokemon heals instead of attacking. */
    const val HEAL_AT = 0.4
    /** A heal their expected hit reaches this share of is undone every turn. */
    const val FUTILE_HEAL = 0.9
    /** Heals in a row after which it attacks instead. */
    const val HEAL_STREAK = 2
    /** Status move score (matchup gain over attacking) at which it replaces the attack. */
    const val STATUS_GAIN = 0.25
    /** The strongest hit's chance to finish the target within two uses at which a debuff gives way to it. */
    const val STATUS_WORSE_KNOCKOUT = 0.5
    /**
     * Predicted switch chance at which the turn is spent on hazards or a stat raise. The switch model reads few switches
     * on a fresh board (365 checks in Boss self-play: 5 at 0.3 or over, 1 at 0.4); a switch seen in the same matchup
     * before moves it up.
     */
    const val FREE_TURN = 0.3
    /** Their knockout chance on a stay at which the free turn is not risked. */
    const val FREE_TURN_RISK = 0.5
    /** Opposing Pokemon left (the one switching included) for hazards to pay. */
    const val HAZARD_TARGETS = 3
    const val FREE_TURN_SETUP_HP = 0.5
    /** Opposing Pokemon a hazard must touch to be worth the turn. */
    const val HAZARD_HIT = 2
    /** The opponent is finished rather than given a free turn at this HP or this knockout chance. */
    const val FINISH_HP = 0.3
    /** A stat raise whose user wins the one-on-one after it less often than this gives the turn away. */
    const val SETUP_WIN = 0.5
    const val FINISH_CHANCE = 0.3
    private const val HEAL_WAIT_HP = 0.5
}
