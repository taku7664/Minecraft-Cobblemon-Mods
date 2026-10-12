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
        /** The ranking's credit per action for the switches it expects the opponent to make ([LocalSwitchPrediction]). */
        predicted: Map<String, Double> = emptyMap(),
        /** Per opposing Pokemon, the chance it switches to each one behind it. */
        expectedSwitches: Map<java.util.UUID, Map<java.util.UUID, Double>> = emptyMap(),
        /** Per debuff action, what it takes off its target's exchanges with this team ([LocalDebuffValue]). */
        debuffGains: Map<String, Double> = emptyMap(),
    ): Verdict? {
        val state = context.state
        val foe = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val self = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return replacement(ranked, excluded, scores, foe)
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
        // has weighed what each does if the opponent switches instead.
        attacks.firstOrNull { (rank, score) ->
            val first = if (LocalPublicTurnOrder.effectivePriority(state, BattleSide.ALLY, part(rank)) > 0) 1.0
                else stay.subjectMovesFirstProbability
            score.knockoutChanceWithin(1) * (first + (1.0 - first) * (1.0 - theirKnockout)) >= KNOCKOUT
        }?.let { (rank, _) -> return Verdict(rank, "knockout") }

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
        fun gain(rank: LocalBattleActionRank) = entry(rank)!!.score - stay.score
        // One the team can spare, knocked out this turn anyway, stays and hits when the hit on entry would cost the
        // next one much: its fall brings that one in without the hit. A healthy one, or one with a switch-in that
        // takes the hit well, is not given away.
        val sacking = reason == "loses_one_on_one" && keep <= LocalSwitchRules.WORTHLESS && attacks.isNotEmpty() &&
            theirKnockout >= LocalSwitchRules.DOOMED && (best == null || best.first.let { rank ->
                val free = scores.pokemon(requireNotNull(part(rank).switchPokemonId), foe.battlePokemonId)?.score ?: return@let false
                free - entry(rank)!!.score >= LocalSwitchRules.FREE_ENTRY_MARGIN
            })
        if (!sacking && reason != null && best != null && (reason in UNCONDITIONAL || gain(best.first) >= margin)) {
            return Verdict(best.first, "switch_$reason")
        }
        if (part(chosen).kind == BattleActionKind.SWITCH) {
            if (sacking) return bestAttack(attacks)?.let { Verdict(it, "sack") }
            val gain = viable.firstOrNull { it.first === chosen }?.let { gain(it.first) }
            // A reason the roll let pass still lets the ranking's own switch stand; without one it must be clear.
            if (gain != null && gain >= (if (reasons.isNotEmpty()) margin else CLEAR_GAIN + margin - MARGIN)) return null
            // A switch without a reason, or into a Pokemon that does not take the hit: stay and attack.
            return bestAttack(attacks)?.let { Verdict(it, "stay_no_reason") }
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
        fun futile(heal: Double) = (hitCapped && self.hpFraction >= heal) || (!hitCapped && theirHit >= heal * FUTILE_HEAL)
        val chosenHeal = healShare(chosenMove)
        if (chosenHeal > 0.0) {
            // The Emerald AI heals under half HP, or two thirds when it moves first, and not into a hit that undoes it.
            val healBelow = if (stay.subjectMovesFirstProbability >= 0.5) HEAL_BELOW_FIRST else HEAL_BELOW
            if (self.hpFraction >= healBelow) return Verdict(strongest, "early_heal")
            if (futile(chosenHeal)) return Verdict(strongest, "futile_heal")
            return null
        }
        val chosenDamage = damage(chosen)
        if (chosenDamage != null) {
            // Low, losing, and a heal that outpaces their hit: heal, unless it has been healing turn after turn.
            val heal = moves.mapNotNull { rank -> healShare(part(rank)).takeIf { it > 0.0 }?.let { rank to it } }.maxByOrNull { it.second }
            val healing = context.memory.lastMoveId?.let { last -> heal?.first?.let { PublicIds.canonical(part(it).moveId.orEmpty()) == PublicIds.canonical(last) } } == true &&
                context.memory.sameMoveRepeatCount >= HEAL_STREAK
            if (heal != null && !healing && self.hpFraction <= HEAL_AT && stay.winProbability < 0.5 && !futile(heal.second)) {
                return Verdict(heal.first, "heal_in_time")
            }
            // Not while it can be finished: low, or already in knockout range.
            val finishing = foe.hpFraction <= FINISH_HP || attacks.any { it.second.knockoutChanceWithin(1) >= FINISH_CHANCE }
            if (!finishing) freeTurn(moves, self, foe, state, scores, expectedSwitches, theirKnockout)?.let { return it }
            // A status move that turns the exchange (a burn on a physical attacker, paralysis on a faster one).
            moves.mapNotNull { rank -> statusScore(rank)?.let { rank to it } }.maxByOrNull { it.second.score }
                ?.takeIf { it.second.score >= STATUS_GAIN }?.let { return Verdict(it.first, "status_better") }
            val top = damage(strongest)!!.score
            // An attack the ranking chose for the Pokemon it expects to come in is not weak: it was aimed past this one.
            val aimedAtSwitch = (predicted[chosen.outcome.candidate.actionId] ?: 0.0) > (predicted[strongest.outcome.candidate.actionId] ?: 0.0)
            return if (!aimedAtSwitch && chosenDamage.score < WEAK_ATTACK * top) Verdict(strongest, "weak_attack") else null
        }
        if (wasted(chosenMove, context)) return Verdict(strongest, "wasted_status")
        // A stat raise is judged as LocalSetupGate.score judges it: whether its user still wins the one-on-one after it,
        // the setup turn's hit paid. Not whether it raises the one-on-one: one already won cannot rise, and a boost
        // that leaves it won is a free one.
        if (LocalSetupGate.raisesOwnStats(chosen.outcome.candidate)) {
            val win = LocalSetupGate.score(chosen.outcome.candidate, context, scores, knockoutMaterialScore = 1.0)?.win
            return if (win != null && win < SETUP_WIN) Verdict(strongest, "unsafe_setup") else null
        }
        // Only a debuff (a status condition or a stat drop on the target: Will-O-Wisp, Metal Sound) gives way to the hit:
        // when it takes nothing off the target's exchanges with this team (LocalDebuffValue), or the hit finishes the
        // target within two uses so the debuff has no time to matter (2026-10-12). Without a sweep reading, the
        // one-on-one exchange score decides as before.
        if (LocalDebuffValue.isDebuff(chosenMove)) {
            val finishedSoon = (damage(strongest)?.knockoutChanceWithin(2) ?: 0.0) >= STATUS_WORSE_KNOCKOUT
            val gain = debuffGains[chosen.outcome.candidate.actionId] ?: statusScore(chosen)?.score
            if (finishedSoon || (gain != null && gain <= 0.0)) return Verdict(strongest, "status_worse")
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
        return moves.firstOrNull { LocalSetupGate.raisesOwnStats(it.outcome.candidate) && !transformsMove(part(it)) }
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
}
