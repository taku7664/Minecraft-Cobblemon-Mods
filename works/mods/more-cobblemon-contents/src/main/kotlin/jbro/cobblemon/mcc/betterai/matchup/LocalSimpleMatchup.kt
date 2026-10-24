package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*
import kotlin.math.ceil

/** Opt-in approximations. Existing attack intervals are inputs, never a request for new damage rolls. */
internal object LocalSimpleMatchup {
    data class Hit(val move: MoveMatchupScore, val action: BattleActionCandidate)

    fun hit(base: BattleDecisionContext, future: BattleDecisionContext, move: MoveMatchupScore, max: Boolean = false): Hit {
        val source = base.state.pokemon.first { it.battlePokemonId == move.userId }
        val actor = future.state.pokemon.first { it.battlePokemonId == move.userId }
        val target = future.state.pokemon.first { it.battlePokemonId == move.targetId }
        val oldTarget = base.state.pokemon.first { it.battlePokemonId == move.targetId }
        val details = base.publicActionCatalog.forPokemon(move.userId)
            .firstOrNull { PublicIds.canonical(it.moveId) == PublicIds.canonical(move.moveId) }?.details
            ?: base.publicActionCatalog.inferredMovesForPokemon(move.userId)?.slots
                ?.firstOrNull { it.moveId?.let(PublicIds::canonical) == PublicIds.canonical(move.moveId) }?.details
            ?: base.publicActionCatalog.candidatePools.firstOrNull {
                it.battlePokemonId == move.userId && it.speciesId == source.speciesId && it.formId == source.formId
            }
                ?.moveDetails?.entries?.firstOrNull { PublicIds.canonical(it.key) == PublicIds.canonical(move.moveId) }?.value
        val original = BattleActionCandidate("simple:${move.userId}:${move.moveId}", BattleActionKind.USE_MOVE,
            source.activeSlot, 0, move.moveId, moveDetails = details)
        val oldAction = LocalDynamax.asMaxMove(original, source)
        val action = LocalDynamax.asMaxMove(original, if (max) LocalDynamax.start(actor) else actor)
        // On the input board the damage interval is the constant itself, not another factor calculation.
        if (!max && base.state === future.state) {
            LocalDecisionInternals.record("simpleHit:${move.userId}:${move.targetId}:${move.moveId}:false:${future.state.turn}", mapOf(
                "baseMin" to move.minimumDamageFraction, "baseMax" to move.maximumDamageFraction,
                "baseFactor" to 0.0, "factor" to 0.0, "ratio" to 1.0, "hp" to target.hpFraction,
                "min" to move.minimumDamageFraction, "max" to move.maximumDamageFraction,
                "ko1" to move.knockoutChanceWithin(1), "baseKo" to move.knockoutChanceWithin(1),
                "accuracy" to move.accuracy, "baseHp" to oldTarget.hpFraction,
                "guard" to 0.0, "shield" to 0.0, "factorResolved" to 0.0, "frozen" to 1.0))
            return Hit(move, action)
        }
        val oldFactor = LocalForecastMatchups.damageFactor(oldAction, base, move.userId, move.targetId)
        val damageBoard = if (!max) future else future.copy(state = future.state.copyState(pokemon = future.state.pokemon.map {
            if (it.battlePokemonId == actor.battlePokemonId) LocalDynamax.start(actor) else it
        }))
        val newFactor = LocalForecastMatchups.damageFactor(action, damageBoard, move.userId, move.targetId)
        // Unresolved/script damage holds the existing interval constant; it never falls back to a full matchup.
        val ratio = if (oldFactor != null && oldFactor > 0.0 && oldFactor.isFinite() && newFactor != null && newFactor.isFinite()) newFactor / oldFactor else 1.0
        var low = (move.minimumDamageFraction * ratio).coerceIn(0.0, target.hpFraction)
        var high = (move.maximumDamageFraction * ratio).coerceIn(low, target.hpFraction)
        val ignore = LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, future.state)
        val single = LocalDeclaredMultiHit.maximumCount(action) <= 1
        val shield = single && !ignore && LocalDisguise.intact(future.state, target)
        val guard = single && LocalFullHealthSurvivalRules.survivesAnySingleHit(future.state, target, ignore)
        if (shield) {
            low = minOf(target.hpFraction, LocalDisguise.chip(future.state, target)); high = low
        } else if (guard) {
            val limit = (target.hpFraction - 1.0 / (target.combatStats?.maxHp?.maximum ?: 1000)).coerceAtLeast(0.0)
            low = minOf(low, limit); high = minOf(high, limit)
        }
        val ko = if (shield || guard) 0.0 else if (ratio == 1.0 && oldTarget.hpFraction == target.hpFraction) move.knockoutChanceWithin(1)
            else move.accuracy * (1.0 - below(low, high, target.hpFraction))
        val scaled = move.copy(minimumDamageFraction = low, maximumDamageFraction = high,
            survivalByUses = listOf(1.0, 1.0 - ko), damageWhileStandingByUses = listOf(0.0, (low + high) / 2),
            priority = if (details == null) move.priority else LocalPublicTurnOrder.effectivePriority(future.state, actor.side, action))
        LocalDecisionInternals.record("simpleHit:${move.userId}:${move.targetId}:${move.moveId}:$max:${future.state.turn}", mapOf(
            "baseMin" to move.minimumDamageFraction, "baseMax" to move.maximumDamageFraction,
            "baseFactor" to (oldFactor ?: 0.0), "factor" to (newFactor ?: 0.0), "ratio" to ratio,
            "hp" to target.hpFraction, "min" to low, "max" to high, "ko1" to ko,
            "accuracy" to move.accuracy, "baseKo" to move.knockoutChanceWithin(1), "baseHp" to oldTarget.hpFraction,
            "guard" to LocalDecisionInternals.flag(guard), "shield" to LocalDecisionInternals.flag(shield),
            "factorResolved" to LocalDecisionInternals.flag(oldFactor != null && oldFactor > 0.0 && newFactor != null)))
        return Hit(scaled, action)
    }

    fun exchange(base: BattleDecisionContext, future: BattleDecisionContext, pair: PokemonMatchupScore): Double =
        continuation(base, future, pair).score

    fun continuation(base: BattleDecisionContext, future: BattleDecisionContext, pair: PokemonMatchupScore): PokemonMatchupScore = LocalHpMatchupAudit.statusRead {
        val own = future.state.pokemon.first { it.battlePokemonId == pair.subjectId }
        val foe = future.state.pokemon.first { it.battlePokemonId == pair.opponentId }
        val mine = pair.subjectMove?.let { hit(base, future, it) }
        val enemy = pair.opponentMove?.let { hit(base, future, it) }
        fun damage(hit: Hit?) = hit?.move?.let { (it.minimumDamageFraction + it.maximumDamageFraction) * .5 * it.accuracy } ?: 0.0
        val d = damage(mine); val e = damage(enemy)
        val first = if (mine != null && enemy != null)
            LocalPublicTurnOrder.actsFirstProbability(future.state, own.side, mine.action, foe.side, enemy.action) ?: .5
            else LocalPublicTurnOrder.speedOrderProbability(future.state, own, foe) ?: .5
        val ours = turns(foe.hpFraction, d); val theirs = turns(own.hpFraction, e)
        val win = when { own.hpFraction <= 0.0 -> 0.0; foe.hpFraction <= 0.0 -> 1.0
            ours < theirs -> 1.0; ours > theirs -> 0.0; else -> first }
        val hp = if (ours.isFinite()) (own.hpFraction - e * (ours - first).coerceAtLeast(0.0)).coerceIn(0.0, own.hpFraction) else own.hpFraction
        val other = if (theirs.isFinite()) (foe.hpFraction - d * (theirs - (1.0 - first)).coerceAtLeast(0.0)).coerceIn(0.0, foe.hpFraction) else foe.hpFraction
        val score = win * (.5 + .5 * hp) - (1.0 - win) * (.5 + .5 * other)
        LocalHpMatchupAudit.simpleStatusExchange()
        LocalDecisionInternals.record("simpleStatus:${pair.subjectId}:${pair.opponentId}:${own.hpFraction}:${own.statStages}:${foe.statStages}:${foe.statusId}", mapOf(
            "ownHp" to own.hpFraction, "foeHp" to foe.hpFraction, "damage" to d, "incoming" to e,
            "ownTurns" to ours.takeIf(Double::isFinite).orZero(), "foeTurns" to theirs.takeIf(Double::isFinite).orZero(),
            "ownCanDamage" to LocalDecisionInternals.flag(d > 0), "foeCanDamage" to LocalDecisionInternals.flag(e > 0),
            "first" to first, "win" to win, "hpOnWin" to hp, "hpOnLoss" to other, "score" to score))
        pair.copy(subjectMove = mine?.move, opponentMove = enemy?.move, subjectMovesFirstProbability = first,
            winProbability = win, subjectRemainingHpOnWin = hp, opponentRemainingHpOnLoss = other, score = score)
    }

    /** Preserve the existing exchange at zero change. Only the representative transition is new;
     * replacing an entire exchange by a fixed attack can otherwise turn a zero first-hit move into a loss.
     */
    fun transition(base: PokemonMatchupScore, before: PokemonMatchupScore, after: PokemonMatchupScore): PokemonMatchupScore {
        val win = (base.winProbability + after.winProbability - before.winProbability).coerceIn(0.0, 1.0)
        val hp = (base.subjectRemainingHpOnWin + after.subjectRemainingHpOnWin - before.subjectRemainingHpOnWin).coerceIn(0.0, 1.0)
        val other = (base.opponentRemainingHpOnLoss + after.opponentRemainingHpOnLoss - before.opponentRemainingHpOnLoss).coerceIn(0.0, 1.0)
        return after.copy(winProbability = win, subjectRemainingHpOnWin = hp, opponentRemainingHpOnLoss = other,
            score = win * (.5 + .5 * hp) - (1.0 - win) * (.5 + .5 * other))
    }

    private fun Double?.orZero() = this ?: 0.0
    private fun turns(hp: Double, damage: Double) = if (damage > 0.0) ceil(hp / damage) else Double.POSITIVE_INFINITY
    internal fun below(low: Double, high: Double, hp: Double): Double = when {
        hp <= low -> 0.0; hp > high -> 1.0; high <= low -> 0.0; else -> (hp - low) / (high - low)
    }

    /** Exact continuous-uniform sum CDF in constant work, replacing the old 64-step integration. */
    internal fun sumBelow(a: Double, b: Double, c: Double, d: Double, hp: Double): Double {
        val x = hp - a - c; val w = b - a; val v = d - c
        if (w <= 0) return below(c, d, hp - a)
        if (v <= 0) return below(a, b, hp - c)
        if (x <= 0) return 0.0
        if (x >= w + v) return 1.0
        fun square(value: Double) = value.coerceAtLeast(0.0).let { it * it }
        return ((square(x) - square(x - w) - square(x - v) + square(x - w - v)) / (2 * w * v)).coerceIn(0.0, 1.0)
    }
}
