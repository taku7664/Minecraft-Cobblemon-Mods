package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * The switching rules, read from [MatchupScores].
 *
 * A voluntary switch is judged by what the incoming Pokemon faces: the hit the opponent chose for the one it
 * replaces, then the exchange ([SwitchInScore]), against staying ([PokemonMatchupScore]).
 *
 * - `switch_in_dies` (ruled out): it does not survive that hit, unless it is a sacrifice.
 * - A clearly better switch: [SWITCH_MARGIN] or more above staying, credited by the difference.
 * - A sacrifice: the one leaving is worth keeping ([PreserveScore]) and about to be knocked out, and the one
 *   coming in is worth little - it takes the hit so the other survives.
 * - A wasted free entry: the one leaving is worth little and the one coming in is valuable. Letting the first
 *   fall brings the second in without a hit, and that is worth more than switching it in now.
 *
 * A replacement after a knockout enters without a hit, so the candidates are compared on the exchange alone.
 * So does the one a pivot brings in once the opponent has moved; before that, it takes the hit. Such a
 * replacement (a pivot's, an Eject Button's) is asked for mid-turn, with the one leaving still standing.
 *
 * A pivot (U-turn, Volt Switch, Parting Shot) is a switch that also acts: credited like a clearly better
 * switch into the best Pokemon behind it, whose entry is free with the chance the pivot moves second.
 * - `pivot_switch_in_dies` (ruled out): the pivot likely moves first and nobody behind it survives the hit
 *   coming in, unless the user is a sacrifice's keeper (about to be knocked out and worth more than the cheapest
 *   one behind it). A direct switch had this check and a pivot did not: a U-turn brought in a teammate at 30% and
 *   then one at 14%, each knocked out on entry.
 *
 * An sweeper facing Unaware or Destiny Bond retreats ([LocalSetupGate.retreatReasons]): a switch taking it out
 * is credited by its sweep score, as long as the incoming Pokemon survives the entry.
 *
 * Adjustments are in the ranking's score units and are added after the search; the search owns the rest.
 */
internal object LocalSwitchRules {
    data class Judgement(
        val exclusions: Map<String, String>,
        val adjustments: Map<String, Double>,
        /** Switches that send a worthless Pokemon in to take the hit for a doomed, valuable one. */
        val sacrifices: Set<String> = emptySet(),
        /**
         * Each candidate's voluntary switches, by slot: the exchange judged them against staying, so the brain prices them
         * from the move the slot gives up (LocalTacticalBrain, the switch anchor).
         */
        val voluntarySlots: Map<String, Set<Int?>> = emptyMap(),
        /** The exchange's gain over staying, signed, in score units, of the voluntary switches not credited above (below [SWITCH_MARGIN], sacrifices aside). */
        val uncreditedGains: Map<String, Double> = emptyMap(),
    ) {
        companion object {
            val NONE = Judgement(emptyMap(), emptyMap())
        }
    }

    /** The preview's opposing Pokemon not seen yet, as hypothetical bench Pokemon on a copy of the board, or null without any. */
    /**
     * What the opposing sets are read from for the preview's Pokemon not yet seen ([previewFoes]): their board, and the
     * catalog with each one's preview move pool as its learnset, as a Pokemon in front has its own. Without the pool a
     * tier that reads the hidden set filled nothing beside the moves it read (self-play, 2026-10-22).
     */
    internal fun previewSetReading(
        context: BattleDecisionContext,
    ): Pair<jbro.cobblemon.mcc.internal.ai.BattleStateView, jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView>? {
        val preview = context.opponentTeamPreview ?: return null
        val (board, foes) = previewFoes(context) ?: return null
        val catalog = context.publicActionCatalog
        val pools = foes.mapNotNull { foe ->
            val slot = preview.pokemon.firstOrNull { java.util.UUID(1, Long.MIN_VALUE + it.previewSlotId) == foe.battlePokemonId }
                ?: preview.pokemon.firstOrNull { it.speciesId == foe.speciesId && it.formId == foe.formId }
            val pool = slot?.moveCandidatePool ?: return@mapNotNull null
            jbro.cobblemon.mcc.internal.ai.BattlePublicMoveCandidatePoolView(foe.battlePokemonId, foe.speciesId, foe.formId,
                pool.moveIds, pool.sourceId, pool.moveDetails)
        }
        return board to jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView(catalog.entries, catalog.originalEntries,
            catalog.candidatePools + pools, catalog.opponentMoveInferences)
    }

    internal fun previewFoes(context: BattleDecisionContext): Pair<jbro.cobblemon.mcc.internal.ai.BattleStateView, List<BattlePokemonStateView>>? {
        val preview = context.opponentTeamPreview ?: return null
        val state = context.state
        val seen = state.pokemon.filter { it.side == BattleSide.OPPONENT }
        if (preview.selectionSize - seen.size <= 0) return null
        val unseen = preview.pokemon.filter { slot -> seen.none { jbro.cobblemon.mcc.betterai.simulation.NativeInBattleFormes.samePublicIdentity(it, slot) } }
        if (unseen.isEmpty()) return null
        val taken = state.pokemon.mapTo(hashSetOf()) { it.battlePokemonId }
        val foes = unseen.map { slot ->
            var id = java.util.UUID(1, Long.MIN_VALUE + slot.previewSlotId)
            while (id in taken) id = java.util.UUID(id.mostSignificantBits + 1, id.leastSignificantBits)
            taken += id
            BattlePokemonStateView(id, BattleSide.OPPONENT, null, slot.speciesId, slot.formId, slot.level,
                1.0, null, emptyMap(), emptySet(), null, null, false, slot.knownTypeIds, slot.combatStats,
                knownVolatileEffectIds = emptySet())
        }
        // derive, not copyState: the remaining counts already hold the Pokemon not seen.
        return state.derive(pokemon = state.pokemon + foes) to foes
    }

    fun judge(candidates: List<BattleActionCandidate>, context: BattleDecisionContext, scores: MatchupScores,
        tier: jbro.cobblemon.mcc.internal.ai.BattleTrainerTier? = null,
        /**
         * Each opposing Pokemon's chance to switch out this turn, leaned to its record (LocalOpponentTendencies): a foe
         * that leaves does not hit the incoming Pokemon. Empty, every foe attacks it.
         */
        foeSwitchChances: Map<java.util.UUID, Double> = emptyMap()): Judgement {
        val state = context.state
        val opponents = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        // A replacement chosen while nothing stands in front (both sides lost their Pokemon at once) faces whatever comes
        // in next: read against every opposing Pokemon still standing. Read against none, Raihan's three replacements all
        // scored alike and one was drawn at random (hand-played doubles, 2026-10-20).
        val revealed = opponents.ifEmpty {
            state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        }
        // None of them seen yet: the team preview's Pokemon still to come, as hypothetical opponents at full HP (species,
        // form, types and public stats only, nothing hidden filled in), on a table worked out with them.
        val (faced, replacementScores) = if (revealed.isNotEmpty()) revealed to scores else previewFoes(context)?.let { (board, foes) ->
            foes to LocalMatchupScoreCalculator.calculate(context.copy(state = board))
        } ?: return Judgement.NONE
        if (faced.isEmpty()) return Judgement.NONE
        val replacementValues = linkedMapOf<String, Double>()
        val exclusions = linkedMapOf<String, String>()
        val adjustments = linkedMapOf<String, Double>()
        val sacrifices = linkedSetOf<String>()
        val voluntarySlots = linkedMapOf<String, MutableSet<Int?>>()
        val uncreditedGains = linkedMapOf<String, Double>()
        val bench = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
        // An sweeper that should retreat, by its sweep score; worked out once per Pokemon.
        // Each Pokemon's worth, the knockout price's (LocalKnockoutWorth): what a sacrifice gives away.
        val worths by lazy { LocalKnockoutWorth.worths(state, scores, 1.0) }
        // What each one still does, unscaled: what a switch keeps by sparing it.
        val standings by lazy { LocalKnockoutWorth.standings(state, scores) }
        // The board at this turn's end without a hit (poison, weather, a curse): a Pokemon that falls there falls staying.
        val turnEnd by lazy { jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector.project(state,
            jbro.cobblemon.mcc.betterai.mechanics.LocalBadPoisonCounter.seed(state).mapValues { (_, done) -> done + 1 }) }
        fun fallsAtTurnEnd(pokemon: BattlePokemonStateView) =
            turnEnd.pokemon.firstOrNull { it.battlePokemonId == pokemon.battlePokemonId }?.let { it.fainted || it.hpFraction <= 0.0 } == true
        val retreatCredits = HashMap<java.util.UUID, Double>()
        fun retreats(pokemon: BattlePokemonStateView): Double = retreatCredits.getOrPut(pokemon.battlePokemonId) {
            if (LocalSetupGate.retreatReasons(pokemon, context, scores, tier = tier).isEmpty()) 0.0
            else scores.sweeps[pokemon.battlePokemonId]?.score ?: 0.0
        }
        // Singles: the board once the Pokemon in front lands its best hit on the one opposite, read once per Pokemon. A free
        // entry after it falls faces that foe worn down by it.
        val hitScores = HashMap<java.util.UUID, MatchupPairs?>()
        fun afterHit(user: BattlePokemonStateView): MatchupPairs? = if (opponents.size != 1) null else hitScores.getOrPut(user.battlePokemonId) {
            val foe = opponents.single()
            val hit = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)?.subjectMove ?: return@getOrPut null
            val left = foe.hpFraction - hit.accuracy * (hit.minimumDamageFraction + hit.maximumDamageFraction) / 2.0
            if (left <= 0.0) return@getOrPut null
            val board = state.derive(pokemon = state.pokemon.map { if (it.battlePokemonId == foe.battlePokemonId) it.copyState(hpFraction = left) else it })
            LocalMatchupScoreCalculator.currentPairs(context.copy(state = board),
                allyIds = bench.map { it.battlePokemonId }.toSet(), opponentIds = setOf(foe.battlePokemonId))
        }
        // A slot that may not move is being asked for a replacement.
        val movingSlots = candidates.flatMap(::parts).filter { it.kind == BattleActionKind.USE_MOVE }.map { it.actorSlot }.toSet()
        for (candidate in candidates) {
            var adjustment = 0.0
            for (part in parts(candidate)) {
                if (part.kind == BattleActionKind.USE_MOVE && pivots(part)) {
                    val user = active(state, part.actorSlot) ?: continue
                    if (pivotBringsInKnockout(part, user, opponents, state, scores)) exclusions[candidate.actionId] = PIVOT_SWITCH_IN_DIES
                    adjustment += pivot(part, user, opponents, state, scores)
                    continue
                }
                if (part.kind != BattleActionKind.SWITCH) continue
                val incoming = state.pokemon.firstOrNull { it.battlePokemonId == part.switchPokemonId } ?: continue
                val replaced = active(state, part.actorSlot)
                if (replaced == null) {
                    replacementValues[part.actionId] = faced.mapNotNull {
                        replacementScores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score
                    }.averageOrNull() ?: continue
                    continue
                }
                if (part.actorSlot !in movingSlots) {
                    replacementValues[part.actionId] = midTurnEntry(incoming, replaced, opponents, state, scores) ?: continue
                    continue
                }
                val verdict = voluntary(incoming, replaced, opponents, scores, ::retreats, worths, bench, foeSwitchChances, ::afterHit, ::fallsAtTurnEnd, standings, part.actionId, context) ?: continue
                verdict.exclusion?.let { exclusions[candidate.actionId] = it }
                if (verdict.sacrifice) sacrifices += candidate.actionId
                voluntarySlots.getOrPut(candidate.actionId) { linkedSetOf() } += part.actorSlot
                uncreditedGains.merge(candidate.actionId, verdict.uncredited, Double::plus)
                adjustment += verdict.adjustment - dynamaxLeft(replaced, opponents, context)
            }
            if (adjustment != 0.0) adjustments[candidate.actionId] = adjustment
        }
        // Replacements are credited against each other, so their average shifts nothing.
        if (replacementValues.isNotEmpty()) {
            val mean = replacementValues.values.average()
            for (candidate in candidates) {
                val values = parts(candidate).mapNotNull { replacementValues[it.actionId] }
                if (values.isEmpty()) continue
                adjustments.merge(candidate.actionId, values.sumOf { (it - mean) * SCORE_SCALE }, Double::plus)
            }
        }
        return Judgement(exclusions, adjustments, sacrifices, voluntarySlots, uncreditedGains)
    }

    /**
     * What switching a Dynamaxed Pokemon out gives up: the rest of its Dynamax (it ends on the switch, and the side has no
     * other), what it adds to its exchange with the Pokemon in front (LocalSinglesProtectReturn.dynamaxTurn, its turns left).
     * Unpriced, a Dynamaxed Giratina at 8% with two turns left was switched out, its Max Move first in line to knock the
     * slower Mega Gyarados out (hand-played Cynthia, 2026-10-20).
     */
    private fun dynamaxLeft(pokemon: BattlePokemonStateView, opponents: List<BattlePokemonStateView>, context: BattleDecisionContext): Double {
        if (!jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(pokemon)) return 0.0
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
        // dynamaxTurn is what a turn of this Dynamax is worth to the side in front (negative when it helps this side), and
        // leaving ends every turn still left (the marker counts this one too). Read the other way round, a Dynamaxed
        // Giratina left on its second turn at no cost (hand-played Cynthia, 2026-10-20).
        val perTurn = opponents.map { foe ->
            -jbro.cobblemon.mcc.betterai.evaluation.LocalSinglesProtectReturn.dynamaxTurn(context, pokemon, foe, pokemon, cache)
        }.average().takeIf { !it.isNaN() } ?: 0.0
        val turns = pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith(jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.TURNS) }
            ?.substringAfter(':')?.toIntOrNull() ?: jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.DURATION
        return jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.board((perTurn * turns).coerceAtLeast(0.0))
    }

    private class Verdict(val exclusion: String?, val adjustment: Double, val sacrifice: Boolean = false, val uncredited: Double = 0.0)

    private fun voluntary(
        incoming: BattlePokemonStateView,
        replaced: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        scores: MatchupScores,
        retreats: (BattlePokemonStateView) -> Double,
        worths: Map<java.util.UUID, Double> = emptyMap(),
        bench: List<BattlePokemonStateView> = emptyList(),
        foeSwitchChances: Map<java.util.UUID, Double> = emptyMap(),
        afterHit: (BattlePokemonStateView) -> MatchupPairs? = { null },
        fallsAtTurnEnd: (BattlePokemonStateView) -> Boolean = { false },
        standings: Map<java.util.UUID, Double> = emptyMap(),
        actionId: String,
        context: BattleDecisionContext,
    ): Verdict? {
        val byFoe = opponents.mapNotNull { foe -> scores.switchIn(incoming.battlePokemonId, foe.battlePokemonId, replaced.battlePokemonId)?.let { foe to it } }
        val entries = byFoe.map { it.second }
        if (entries.isEmpty()) return null
        // Keep the existing board's matchup and worth. If hazards kill the incoming Pokemon, charge only the HP
        // the saved Pokemon loses on re-entry, in the same HP-bar units as gain (user, 2026-10-22).
        val returns = entries.any { it.entryFaints }
        val reentryHpLoss = if (returns) minOf(replaced.hpFraction,
            jbro.cobblemon.mcc.betterai.mechanics.PublicSwitchEntryHazardCalculator.hpLoss(context.state, replaced.side, replaced) ?: 0.0)
            else 0.0
        val stays = opponents.mapNotNull { scores.pokemon(replaced.battlePokemonId, it.battlePokemonId) }
        // One opponent focusing the slot is enough to knock the incoming Pokemon out; one that switches out hits nothing.
        val survival = byFoe.minOf { (foe, entry) ->
            val hits = 1.0 - (foeSwitchChances[foe.battlePokemonId] ?: 0.0).coerceIn(0.0, 1.0)
            if (entry.entryFaints) 0.0 else 1.0 - (1.0 - entry.predictedSurvival) * hits
        }
        val switchValue = entries.map { it.score }.average()
        val stayValue = stays.map { it.score }.averageOrNull() ?: 0.0
        val keepLeaving = scores.preserves[replaced.battlePokemonId]?.score ?: 0.0
        val retreat = retreats(replaced)
        val keepIncoming = scores.preserves[incoming.battlePokemonId]?.score ?: 0.0
        val leavingDoomed = stays.any { it.winProbability < 0.5 && (it.opponentMove?.knockoutChanceWithin(1) ?: 0.0) >= DOOMED }
        // The one taking the hit must be worth less than the one it saves. Read as its share of the team's coverage, a
        // full-HP Dragapult others could stand in for counted as worthless and was thrown in front of a Dynamaxed
        // Aegislash to save a doomed 44% Rillaboom (dev server, 2026-10-20).
        val incomingWorth = worths[incoming.battlePokemonId]
        val leavingWorth = worths[replaced.battlePokemonId]
        val cheaper = if (incomingWorth != null && leavingWorth != null) incomingWorth < leavingWorth else keepIncoming <= WORTHLESS
        val sacrifice = leavingDoomed && keepLeaving >= WORTH_KEEPING && cheaper
        var adjustment = 0.0
        // Staying, the Pokemon falls this turn at the chance it is knocked out, and then the best one behind it walks in
        // free: that, not its lost matchup, is what the switch is weighed against. Read as the lost matchup alone, a 29%
        // Torkoal in front of a Tyranitar was switched for an Arcanine that took a Stone Edge to 10% (hand-played Kabu,
        // 2026-10-20), where losing the Torkoal let the same Arcanine in untouched.
        // At this turn's end too, after it has moved: a 4% badly poisoned Deoxys that moved first was switched out as if it
        // could stay, where its Psycho Boost would land and the poison bring the Lycanroc behind it in free (self-play, 2026-10-21).
        val hitFalls = 1.0 - stays.fold(1.0) { standing, stay -> standing * (1.0 - (stay.opponentMove?.knockoutChanceWithin(1) ?: 0.0)) }
        val falls = if (fallsAtTurnEnd(replaced)) 1.0 else hitFalls
        val freeAfter = bench.mapNotNull { other ->
            opponents.mapNotNull { scores.pokemon(other.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull()
        }.maxOrNull()
        val fallsFirst = 1.0 - stays.fold(1.0) { standing, stay ->
            standing * (1.0 - (stay.opponentMove?.knockoutChanceWithin(1) ?: 0.0) * (1.0 - stay.subjectMovesFirstProbability))
        }
        // One that moves first and then falls gets its hit in: the one walking in free faces the foe it wore down (singles;
        // the board read again at the HP that hit leaves). Read against the foe untouched, a 26% Tauros whose Close Combat
        // would land on an Archaludon first was switched for an Azelf the Archaludon beat (self-play, 2026-10-21).
        val freeAfterHit = if (falls - fallsFirst <= 0.0) freeAfter else afterHit(replaced)?.let { worn ->
            bench.mapNotNull { other -> opponents.mapNotNull { worn.pokemon(other.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull() }.maxOrNull()
        } ?: freeAfter
        val stayGoesOn = if (freeAfter == null) stayValue else (1.0 - falls) * stayValue + fallsFirst * maxOf(stayValue, freeAfter) +
            (falls - fallsFirst).coerceAtLeast(0.0) * maxOf(stayValue, freeAfterHit ?: freeAfter)
        // The exchange counts the incoming Pokemon's fall on entry as a lost exchange (-1); the switch goes on with the best
        // one left walking in free, the one leaving included, and the fall itself is paid by its worth below. Counted as -1
        // and by its worth, a fall was paid twice.
        val freeAfterIncoming = (bench.filter { it.battlePokemonId != incoming.battlePokemonId } + replaced).mapNotNull { other ->
            opponents.mapNotNull { scores.pokemon(other.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull()
        }.maxOrNull()
        val switchGoesOn = if (freeAfterIncoming == null) switchValue
            else entries.map { it.score + (1.0 - it.predictedSurvival) * (1.0 + freeAfterIncoming) }.average()
        // What the switch keeps: the leaving Pokemon, at the chance it would fall, by what it still does (below), less what
        // the incoming Pokemon risks on the way in, the same way (board units; one unit is SCORE_SCALE points, as a matchup
        // score). Without it a +2 Mega
        // Garchomp at 46% in front of a faster Dragapult stayed for the free entry behind it, where a Togekiss walked in
        // immune (hand-played Cynthia T6, replayed 2026-10-20). What it does is read at the HP it has. The hit one that moves
        // first gets in staying is in the free entry after it (above): read instead as its worth times its HP, a 26% Tauros
        // counted twice over at a quarter of its worth. A sacrifice is the same trade: a cheap Pokemon falling to keep a
        // valuable one; its flat credit (its coverage share times 200) took no account of the one it throws away, and
        // a Volbeat with a seventh of a chance to survive the entry was thrown in for a 37% Tinkaton that moved first.
        val living = jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.livingPokemonValue
        // In what each still does (standings, board units), not the side-shared worth: sparing one that loses to everything
        // keeps little.
        val standingKept = standings[replaced.battlePokemonId] ?: (leavingWorth ?: 0.0) * living
        val standingLost = standings[incoming.battlePokemonId] ?: (incomingWorth ?: 0.0) * living
        val kept = if (freeAfter == null) 0.0 else falls * standingKept - (1.0 - survival) * standingLost
        val gain = switchGoesOn - stayGoesOn + kept - reentryHpLoss
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("switch:$actionId", mapOf(
            "entryFaints" to if (entries.any { it.entryFaints }) 1.0 else 0.0,
            "entryHp" to entries.map { it.hpAfterEntry }.average(),
            "returns" to if (returns) 1.0 else 0.0,
            "keptHp" to replaced.hpFraction - reentryHpLoss,
            "reentryHpLoss" to reentryHpLoss, "reentryCost" to reentryHpLoss * SCORE_SCALE,
            "survival" to survival, "switchValue" to switchValue, "stayValue" to stayValue,
            "falls" to falls, "fallsFirst" to fallsFirst, "freeAfter" to (freeAfter ?: 0.0),
            "freeAfterIncoming" to (freeAfterIncoming ?: 0.0), "switchGoesOn" to switchGoesOn,
            "stayGoesOn" to stayGoesOn, "standingKept" to standingKept, "standingLost" to standingLost,
            "kept" to kept, "gain" to gain,
        ))
        if (gain >= SWITCH_MARGIN) adjustment += gain * SCORE_SCALE
        if (retreat > 0.0 && survival >= SURVIVAL_PASS) adjustment += retreat * SCORE_SCALE
        if (keepLeaving <= WORTHLESS && keepIncoming >= WORTH_KEEPING) {
            // What the incoming Pokemon would face walking in after a knockout, without the hit.
            val free = opponents.mapNotNull { scores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull()
            val freeEntryGain = free?.let { it - switchValue } ?: 0.0
            if (freeEntryGain >= FREE_ENTRY_MARGIN) adjustment -= freeEntryGain * SCORE_SCALE
        }
        val exclusion = if (survival < SURVIVAL_PASS && !sacrifice) SWITCH_IN_DIES else null
        val uncredited = if (gain >= SWITCH_MARGIN) 0.0 else gain * SCORE_SCALE
        return Verdict(exclusion, adjustment, sacrifice, uncredited)
    }

    /**
     * Whether [move] likely goes first and every Pokemon behind [user] is knocked out by the hit it walks into, the
     * user not being worth that sacrifice.
     */
    private fun pivotBringsInKnockout(
        move: BattleActionCandidate,
        user: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): Boolean {
        val bench = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
        if (bench.isEmpty()) return false
        if (opponents.maxOf { pivotFirst(move, user, it, state) } < PIVOT_FIRST_PASS) return false
        val anySurvives = bench.any { incoming ->
            val survival = opponents.mapNotNull { scores.switchIn(incoming.battlePokemonId, it.battlePokemonId, user.battlePokemonId)?.predictedSurvival }
            survival.isEmpty() || survival.min() >= SURVIVAL_PASS
        }
        if (anySurvives) return false
        val stays = opponents.mapNotNull { scores.pokemon(user.battlePokemonId, it.battlePokemonId) }
        val userDoomed = stays.any { it.winProbability < 0.5 && (it.opponentMove?.knockoutChanceWithin(1) ?: 0.0) >= DOOMED }
        val keepUser = scores.preserves[user.battlePokemonId]?.score ?: 0.0
        val cheapest = bench.minOf { scores.preserves[it.battlePokemonId]?.score ?: 0.0 }
        return !(userDoomed && keepUser >= WORTH_KEEPING && cheapest <= WORTHLESS)
    }

    /** The chance [move] goes before [opponent]'s attack, which is taken to have no priority. */
    private fun pivotFirst(
        move: BattleActionCandidate,
        user: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
        state: BattleStateView,
    ): Double {
        val priority = LocalPublicTurnOrder.effectivePriority(state, BattleSide.ALLY, move)
        return when {
            priority > 0 -> 1.0
            priority < 0 -> 0.0
            else -> LocalPublicTurnOrder.speedOrderProbability(state, user, opponent) ?: 0.5
        }
    }

    /** A pivot's credit: the best switch behind it, entering free with the chance the pivot moves second. */
    private fun pivot(
        move: BattleActionCandidate,
        user: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): Double {
        val stayValue = opponents.mapNotNull { scores.pokemon(user.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull() ?: return 0.0
        val bench = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
        val best = bench.mapNotNull { incoming ->
            opponents.mapNotNull { opponent ->
                val free = scores.pokemon(incoming.battlePokemonId, opponent.battlePokemonId)?.score ?: return@mapNotNull null
                val hit = scores.switchIn(incoming.battlePokemonId, opponent.battlePokemonId, user.battlePokemonId)?.score ?: free
                val first = pivotFirst(move, user, opponent, state)
                first * hit + (1.0 - first) * free
            }.averageOrNull()
        }.maxOrNull() ?: return 0.0
        val gain = best - stayValue
        return if (gain >= SWITCH_MARGIN) gain * SCORE_SCALE else 0.0
    }

    /** A mid-turn replacement: free against an opponent that has moved this turn, under its hit otherwise. */
    private fun midTurnEntry(
        incoming: BattlePokemonStateView,
        replaced: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): Double? = opponents.mapNotNull { opponent ->
        val free = scores.pokemon(incoming.battlePokemonId, opponent.battlePokemonId)?.score ?: return@mapNotNull null
        val moved = state.observedEvents.any {
            it.turn == state.turn && it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == opponent.battlePokemonId
        }
        if (moved) free else scores.switchIn(incoming.battlePokemonId, opponent.battlePokemonId, replaced.battlePokemonId)?.score ?: free
    }.averageOrNull()

    private fun pivots(move: BattleActionCandidate): Boolean =
        move.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.SWITCH_USER }

    private fun active(state: BattleStateView, slot: Int?): BattlePokemonStateView? = state.pokemon.firstOrNull {
        it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0
    }

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    const val SWITCH_IN_DIES = "switch_in_dies"
    const val PIVOT_SWITCH_IN_DIES = "pivot_switch_in_dies"
    /** A pivot this likely to go first brings its replacement in under the hit. */
    const val PIVOT_FIRST_PASS = 0.5
    const val SURVIVAL_PASS = 0.5
    const val SWITCH_MARGIN = 0.5
    const val FREE_ENTRY_MARGIN = 0.3
    const val DOOMED = 0.8
    const val WORTH_KEEPING = 0.25
    const val WORTHLESS = 0.05
    /** One point of matchup score in ranking units, the scale a board point has in the search. */
    const val SCORE_SCALE = 100.0
}
