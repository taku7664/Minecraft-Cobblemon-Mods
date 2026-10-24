package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import kotlin.math.ceil
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*

/** Long-term forecasts only. A future position never generates actions or runs the tactical damage calculator. */
internal object LocalForecastMatchups {
    private data class Template(val moveId: String, val action: BattleActionCandidate, val raw: List<Double>, val factor: Double?)
    private data class World(val abilities: Map<UUID, String>, val moves: List<Template>)
    private data class Seed(val worlds: Map<String, World>)

    private fun lent(context: BattleDecisionContext, abilities: Map<UUID, String>): BattleDecisionContext =
        if (abilities.isEmpty()) context else context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
            abilities[it.battlePokemonId]?.let { ability -> it.copyState(knownAbilityId = ability) } ?: it
        }))

    private fun seed(base: BattleDecisionContext, user: UUID, target: UUID, cache: LocalProjectedActionCalculationCache): Seed =
        cache.forecastSeed(base, user, target) { LocalHpMatchupAudit.seed {
            val uncertain = base.state.pokemon.filter {
                it.battlePokemonId in setOf(user, target) && it.side == BattleSide.OPPONENT && it.knownAbilityId == null
            }
            var worlds = listOf(emptyMap<UUID, String>())
            for (pokemon in uncertain) {
                val abilities = jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.publicPossibleAbilities(base.state, pokemon).sorted()
                if (abilities.isNotEmpty()) worlds = worlds.flatMap { world -> abilities.map { world + (pokemon.battlePokemonId to it) } }
            }
            val actor = base.state.pokemon.first { it.battlePokemonId == user }
            val defender = base.state.pokemon.first { it.battlePokemonId == target }
            // Preparing a forecast must not run a complete matchup (including repeated-hit KO tables) first.
            val actions = cache.slotActions(base.state, actor.side, base.publicActionCatalog, actor.side == BattleSide.OPPONENT) {
                PublicFutureActionFactory.slotActions(base.state, actor.side, base.publicActionCatalog,
                    includeMoveHypotheses = actor.side == BattleSide.OPPONENT)
            }.asSequence().filter { it.kind == BattleActionKind.USE_MOVE && it.actorSlot == actor.activeSlot &&
                it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS && !LocalDynamax.isMaxMove(it.moveId) }
                .map { LocalDynamax.asMaxMove(it, actor) }
                .filter { it.moveId !in LocalMatchupScoreCalculator.FAILS_WHEN_HIT_FIRST && LocalConsecutiveMoveRules.canAttempt(it, base.state, actor) }
                .mapNotNull { action ->
                    val targets = LocalPublicMoveTargets.resolve(action, base, actor.side)
                    when {
                        targets.firstOrNull()?.battlePokemonId == target -> action
                        targets.size > 1 && targets.any { it.battlePokemonId == target } ->
                            LocalPublicMoveTargets.spreadHitOn(action, defender, "${action.actionId}:at:${defender.side}:${defender.activeSlot}")
                        else -> null
                    }
                }.toList()
            Seed(worlds.associate { abilities ->
                val board = lent(base, abilities)
                val templates = actions.mapNotNull { original ->
                    val actor = board.state.pokemon.first { it.battlePokemonId == user }
                    val action = PublicBattleTacticalCalculator.forecastAction(original)
                    val acting = board.copy(state = LocalStanceChangeStateProjector.beforeMove(board.state, actor.side, action))
                    val raw = PublicBattleTacticalCalculator.uncappedConservativeDamageRollFractions(action, acting, actor.side)
                        ?: return@mapNotNull null
                    Template(LocalDynamax.baseMoveId(original) ?: PublicIds.canonical(original.moveId.orEmpty()),
                        action, raw, damageFactor(action, acting, user, target))
                }
                abilities.entries.sortedBy { it.key.toString() }.joinToString { "${it.key}=${it.value}" } to World(abilities, templates)
            })
        } }

    /** Relative power x effective attack / effective defence x damage modifiers, without damage-roll projection. */
    internal fun damageFactor(action: BattleActionCandidate, board: BattleDecisionContext, user: UUID, targetId: UUID): Double? {
        val state = board.state
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == user } ?: return null
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return null
        val details = action.moveDetails ?: return null
        val input = LocalPublicMoveDamageInputs.resolve(action, actor, target, state) ?: return null
        val stats = input.offensivePokemon.combatStats ?: return null
        val targetStats = target.combatStats ?: return null
        fun stat(stat: LocalPublicMoveDamageInputs.CombatStat): BattleIntegerRange = when (stat) {
            LocalPublicMoveDamageInputs.CombatStat.ATTACK -> stats.attack
            LocalPublicMoveDamageInputs.CombatStat.DEFENCE -> stats.defence
            LocalPublicMoveDamageInputs.CombatStat.SPECIAL_ATTACK -> stats.specialAttack
            LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE -> stats.specialDefence
        }
        val critical = LocalCriticalHitRules.confirmed(action, actor, target, state)
        val ignore = LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, state)
        val offensiveUnaware = LocalPublicAbilityState.effectiveKnownAbility(state, target) == "unaware" && !ignore &&
            input.offensivePokemon.battlePokemonId == user && input.offensiveStat in setOf(
                LocalPublicMoveDamageInputs.CombatStat.ATTACK, LocalPublicMoveDamageInputs.CombatStat.SPECIAL_ATTACK)
        val defensiveUnaware = LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "unaware"
        val effects = details.effects?.effects.orEmpty()
        val stolen = if (effects.any { it.kind == BattleMoveEffectKind.STEALS_STAT_STAGES })
            target.statStages[if (details.damageCategory == BattleMoveDamageCategory.PHYSICAL) "attack" else "special_attack"]?.coerceAtLeast(0) ?: 0 else 0
        val attackStage = (input.offensiveStage + stolen).coerceIn(-6, 6).let { if (offensiveUnaware || critical && it < 0) 0 else it }
        val defenceStage = input.defensiveStage.let {
            if (defensiveUnaware || it > 0 && (critical || effects.any { effect -> effect.kind == BattleMoveEffectKind.IGNORE_DEFENSIVE_STAGES })) 0 else it
        }
        val attack = PublicBattleTacticalCalculator.publicOffensiveStat(
            PublicBattleTacticalCalculator.applyStage(stat(input.offensiveStat), attackStage), action.moveId, details, actor, input, state)
        val defence = LocalKnownStatMechanics.defence(PublicBattleTacticalCalculator.applyStage(
            if (input.defensiveStat == LocalPublicMoveDamageInputs.CombatStat.DEFENCE) targetStats.defence else targetStats.specialDefence,
            defenceStage), input.defensiveStat, target, state)
        val power = LocalKnownStatMechanics.effectivePower(input.powers, actor, state, action)
        val ours = actor.side == BattleSide.ALLY
        val a = if (ours) attack.minimum else attack.maximum
        val d = if (ours) defence.maximum else defence.minimum
        val p = if (ours) power.minimum else power.maximum
        val maxHp = if (ours) targetStats.maxHp.maximum else targetStats.maxHp.minimum
        val type = StandardTypeEffectiveness.multiplierForMove(action.moveId, details.typeId, target.knownTypeIds)
        val modifier = LocalPublicMechanicsKernel.projectMove(action, board, actor.side).knownDamageMultiplier *
            LocalKnownStatMechanics.damageMultiplier(actor, type, state) *
            (LocalPublicStab.multiplier(action, actor, details.typeId) ?: 1.0) * type *
            LocalPublicMoveTargets.spreadMultiplier(action, board, actor.side)
        return if (d <= 0 || maxHp <= 0) null else p.toDouble() * a / d * modifier / maxHp
    }

    private fun moves(world: World, future: BattleDecisionContext, user: UUID, targetId: UUID): List<MoveMatchupScore> {
        val state = future.state
        val actor = state.pokemon.first { it.battlePokemonId == user }
        val target = state.pokemon.first { it.battlePokemonId == targetId }
        return world.moves.map { template ->
            val action = template.action
            val acting = future.copy(state = LocalStanceChangeStateProjector.beforeMove(state, actor.side, action))
            val projection = LocalPublicMechanicsKernel.projectMove(action, acting, actor.side)
            val factor = damageFactor(action, acting, user, targetId)
            val ratio = if (template.factor != null && template.factor > 0.0 && factor != null) factor / template.factor else 1.0
            val direct = PublicBattleTacticalCalculator.declaredDamageRollFractions(action, actor, target, projection, state)
            val rolls = direct ?: if (projection.publiclyNullified) listOf(0.0) else template.raw.map { it * ratio }
            val accuracy = LocalPublicAccuracy.probability(action, acting, actor.side)
            val ignore = LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, state)
            val guard = LocalDeclaredMultiHit.maximumCount(action) <= 1 && LocalFullHealthSurvivalRules.survivesAnySingleHit(state, target, ignore)
            val limit = if (guard) (target.hpFraction - 1.0 / (target.combatStats?.maxHp?.maximum ?: 1000)).coerceAtLeast(0.0) else null
            val input = LocalPublicMoveDamageInputs.resolve(action, actor, target, state)
            val statNames = when (input?.offensiveStat) {
                LocalPublicMoveDamageInputs.CombatStat.ATTACK -> setOf("attack", "atk")
                LocalPublicMoveDamageInputs.CombatStat.DEFENCE -> setOf("defence", "defense", "def")
                LocalPublicMoveDamageInputs.CombatStat.SPECIAL_ATTACK -> setOf("special_attack", "specialattack", "spa")
                LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE -> setOf("special_defence", "specialdefense", "spd")
                null -> emptySet()
            }
            val changes = action.moveDetails?.effects?.effects.orEmpty().filter {
                it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER &&
                    (it.probability ?: 1.0) >= 1.0 && it.statStages.keys.any { key -> key in statNames }
            }.takeUnless { LocalDynamax.active(actor) }.orEmpty()
            val means = if (changes.isEmpty()) listOf(rolls.average()) else buildList {
                add(rolls.average())
                var repeated = acting.state
                repeat(LocalMatchupScoreCalculator.MAXIMUM_USES - 1) {
                    for (effect in changes) repeated = LocalStatStageChange.apply(repeated, user, user, effect.statStages)
                    val changed = damageFactor(action, acting.copy(state = repeated), user, targetId)
                    add(if (template.factor != null && template.factor > 0.0 && changed != null)
                        template.raw.average() * changed / template.factor else rolls.average())
                }
            }
            var profile = profile(rolls, accuracy, target.hpFraction, limit, means)
            val sleeping = LocalSleepClock.remaining(state, actor)
            val talks = if (LocalSleepClock.talks(actor)) LocalSleepClock.SLEEP_TALK_SHARE else 0.0
            val delay = sleeping?.sumOf { (turns, chance) -> turns * chance * (1.0 - talks) } ?: 0.0
            val paralysis = if (PublicIds.canonical(actor.statusId.orEmpty()) in setOf("par", "paralysis")) LocalMatchupScoreCalculator.PARALYSIS_ACTS else 1.0
            val now = LocalKnockoutWorth.actsThisTurn(actor, state)
            profile = LocalKnockoutProfile.forecast(profile.survival.mapIndexed { i, value ->
                if (i == 1) 1.0 - (1.0 - value) * now else value
            }, profile.damageWhileStanding, profile.expectedUses / paralysis + delay)
            if (LocalDisguise.intact(state, target) && !ignore) profile = profile.behindDisguise(LocalDisguise.chip(state, target))
            if (LocalTruant.active(state, actor) || LocalConsecutiveMoveRules.needsGap(action, actor))
                profile = profile.withGapBetweenUses(!LocalTruant.active(state, actor) || !LocalTruant.loafs(state, actor))
            val selfKo = action.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.SELF_DESTRUCT }
            val first = rolls.map { minOf(it, limit ?: target.hpFraction) }
            val expectedUses = if (selfKo && profile.survival[1] > 0.0) Double.POSITIVE_INFINITY else profile.expectedUses
            val result = MoveMatchupScore(user, template.moveId, targetId, accuracy,
                first.minOrNull() ?: 0.0, first.maxOrNull() ?: 0.0, profile.survival, profile.damageWhileStanding,
                expectedUses,
                if (expectedUses.isFinite() && expectedUses > 0.0) 1.0 / expectedUses else 0.0,
                if (selfKo) 1.0 else 0.0, LocalPublicTurnOrder.effectivePriority(state, actor.side, action))
            LocalDecisionInternals.record("forecastMove:$user:$targetId:${action.moveId}", mapOf(
                "baseMean" to template.raw.average(), "baseFactor" to (template.factor ?: 0.0), "futureFactor" to (factor ?: 0.0),
                "ratio" to ratio, "direct" to LocalDecisionInternals.flag(direct != null), "mean" to rolls.average(),
                "accuracy" to accuracy, "hp" to target.hpFraction, "lastMean" to means.last(),
                "factorResolved" to LocalDecisionInternals.flag(template.factor != null && factor != null), "expectedUses" to result.expectedHitsToKnockout.takeIf(Double::isFinite).orZero()))
            result
        }
    }

    private fun Double?.orZero() = this ?: 0.0

    /** Small hit-count approximation. First-use KO and miss probability stay on actual scaled rolls. */
    internal fun profile(rolls: List<Double>, accuracy: Double, hp: Double, limit: Double? = null,
        means: List<Double> = listOf(rolls.average())): LocalKnockoutProfile {
        val p = accuracy.coerceIn(0.0, 1.0)
        val first = rolls.map { minOf(it, limit ?: hp) }
        val q = first.count { it >= hp }.toDouble() / first.size.coerceAtLeast(1)
        val survived = first.filter { it < hp }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val mean = rolls.average().coerceAtLeast(0.0)
        val hits = when {
            mean <= 0.0 -> Double.POSITIVE_INFINITY
            q >= 1.0 -> 1.0
            means.size <= 1 -> (1.0 + ceil((hp - survived).coerceAtLeast(0.0) / mean)).coerceAtLeast(2.0)
            else -> {
                var left = (hp - survived).coerceAtLeast(0.0)
                var landed = 1
                while (left > 0.0 && landed < means.size) { left -= means[landed]; landed++ }
                if (left <= 0.0) landed.toDouble() else if (means.last() <= 0.0) Double.POSITIVE_INFINITY
                else landed + ceil(left / means.last())
            }
        }
        val survival = ArrayList<Double>(); val damage = ArrayList<Double>()
        for (n in 0..LocalMatchupScoreCalculator.MAXIMUM_USES) {
            var alive = 0.0; var taken = 0.0
            var mass = if (p == 1.0) 0.0 else Math.pow(1.0 - p, n.toDouble())
            for (k in 0..n) {
                val probability = if (p == 1.0) (if (k == n) 1.0 else 0.0) else mass
                val standing = when { k == 0 -> 1.0; k == 1 -> 1.0 - q; k >= hits -> 0.0; else -> 1.0 - q }
                alive += probability * standing
                taken += probability * standing * if (k == 0) 0.0 else minOf(hp, survived + (1 until k).sumOf { means[minOf(it, means.lastIndex)] })
                if (p < 1.0 && k < n) mass *= (n - k).toDouble() / (k + 1) * p / (1.0 - p)
            }
            survival += alive.coerceIn(0.0, 1.0); damage += if (alive > 0.0) taken / alive else 0.0
        }
        val expected = if (p <= 0.0 || mean <= 0.0) Double.POSITIVE_INFINITY else (1.0 + (1.0 - q) * (hits - 1.0)) / p
        return LocalKnockoutProfile.forecast(survival, damage, expected)
    }

    internal fun action(base: BattleDecisionContext, user: UUID, target: UUID, move: String,
        cache: LocalProjectedActionCalculationCache): BattleActionCandidate? = seed(base, user, target, cache).worlds.values
        .firstOrNull()?.moves?.firstOrNull { it.moveId == move }?.action

    internal fun forecastMoves(base: BattleDecisionContext, future: BattleDecisionContext, user: UUID, target: UUID,
        cache: LocalProjectedActionCalculationCache): List<LocalMatchupScoreCalculator.ScoredMove> {
        val seed = seed(base, user, target, cache)
        val all = seed.worlds.values.flatMap { world -> moves(world, lent(future, world.abilities), user, target) }
        return all.groupBy { it.moveId }.mapNotNull { (id, scores) ->
            val action = action(base, user, target, id, cache) ?: return@mapNotNull null
            val profile = scores.first().survivalByUses.indices.map { n -> scores.map { it.survivalByUses[n] }.average() }
            LocalMatchupScoreCalculator.ScoredMove(scores.first().copy(survivalByUses = profile), action)
        }
    }

    fun exchange(base: BattleDecisionContext, future: BattleDecisionContext, subject: UUID, foe: UUID,
        field: MatchupSpeedField, cache: LocalProjectedActionCalculationCache): PokemonMatchupScore? {
        val own = future.state.pokemon.firstOrNull { it.battlePokemonId == subject } ?: return null
        val other = future.state.pokemon.firstOrNull { it.battlePokemonId == foe } ?: return null
        val ours = seed(base, subject, foe, cache)
        val theirs = seed(base, foe, subject, cache)
        val readings = ours.worlds.mapNotNull { (key, world) ->
            val reply = theirs.worlds[key] ?: return@mapNotNull null
            val board = lent(future, world.abilities)
            val a = moves(world, board, subject, foe).sortedBy { it.expectedHitsToKnockout }.take(LocalMatchupScoreCalculator.EXCHANGE_MOVES).ifEmpty { listOf(null) }
            val b = moves(reply, board, foe, subject).sortedBy { it.expectedHitsToKnockout }.take(LocalMatchupScoreCalculator.EXCHANGE_MOVES).ifEmpty { listOf(null) }
            val heal = if (cache.matchupRecovery) LocalMatchupScoreCalculator.healFraction(board, own) else 0.0
            val foeHeal = if (cache.matchupRecovery) LocalMatchupScoreCalculator.healFraction(board, other) else 0.0
            a.map { mine -> b.map { enemy -> pair(board, own, other, mine, enemy, world, reply, field, heal, foeHeal) }.minBy { it.score } }.maxBy { it.score }
        }
        if (readings.isEmpty()) return null
        LocalHpMatchupAudit.simpleExchange()
        val win = readings.map { it.winProbability }.average()
        val hp = readings.sumOf { it.winProbability * it.subjectRemainingHpOnWin }.let { if (win > 0.0) it / readings.size / win else own.hpFraction }
        val enemyHp = readings.sumOf { it.lossProbability * it.opponentRemainingHpOnLoss }.let { if (win < 1.0) it / readings.size / (1.0 - win) else other.hpFraction }
        val score = win * (.5 + .5 * hp) - (1.0 - win) * (.5 + .5 * enemyHp)
        LocalDecisionInternals.record("forecast:$subject:$foe:$field", mapOf("worlds" to readings.size.toDouble(),
            "win" to win, "hpOnWin" to hp, "hpOnLoss" to enemyHp, "score" to score))
        return readings.first().copy(winProbability = win, subjectRemainingHpOnWin = hp, opponentRemainingHpOnLoss = enemyHp, score = score)
    }

    private fun pair(board: BattleDecisionContext, own: BattlePokemonStateView, foe: BattlePokemonStateView,
        mine: MoveMatchupScore?, enemy: MoveMatchupScore?, ours: World, theirs: World, field: MatchupSpeedField,
        heal: Double, foeHeal: Double): PokemonMatchupScore {
        fun action(world: World, move: MoveMatchupScore?) = world.moves.firstOrNull { it.moveId == move?.moveId }?.action
        val myAction = action(ours, mine); val enemyAction = action(theirs, enemy)
        val first = if (myAction != null && enemyAction != null)
            LocalPublicTurnOrder.actsFirstProbability(board.state, own.side, myAction, foe.side, enemyAction) ?: .5
        else LocalPublicTurnOrder.speedOrderProbability(board.state, own, foe) ?: .5
        val d = mine?.let { it.accuracy * (it.minimumDamageFraction + it.maximumDamageFraction) / 2 } ?: 0.0
        val e = enemy?.let { it.accuracy * (it.minimumDamageFraction + it.maximumDamageFraction) / 2 } ?: 0.0
        fun turns(move: MoveMatchupScore?, victim: BattlePokemonStateView, hit: Double): Double {
            val ordinary = move?.expectedHitsToKnockout ?: Double.POSITIVE_INFINITY
            val schedule = LocalResidual.schedule(board.state, victim, LocalMatchupScoreCalculator.MAXIMUM_USES) ?: return ordinary
            var left = victim.hpFraction
            for ((index, residual) in schedule.withIndex()) {
                left -= hit + residual
                if (left <= 0.0) return index + 1.0
            }
            val rate = hit + (schedule.lastOrNull() ?: 0.0)
            return if (rate > 0.0) schedule.size + left / rate else Double.POSITIVE_INFINITY
        }
        val ownTurns = if (foeHeal > d && d > 0.0) Double.POSITIVE_INFINITY else turns(mine, foe, d)
        val foeTurns = if (heal > e && e > 0.0) Double.POSITIVE_INFINITY else turns(enemy, own, e)
        val q = mine?.knockoutChanceWithin(1) ?: 0.0; val r = enemy?.knockoutChanceWithin(1) ?: 0.0
        val ownTail = if (ownTurns.isFinite() && q < 1.0) (ownTurns - 1.0).coerceAtLeast(0.0) / (1.0 - q) else ownTurns
        val foeTail = if (foeTurns.isFinite() && r < 1.0) (foeTurns - 1.0).coerceAtLeast(0.0) / (1.0 - r) else foeTurns
        val tailWin = when { ownTail < foeTail -> 1.0; ownTail > foeTail -> 0.0; else -> first }
        val win = when { own.fainted || own.hpFraction <= 0.0 -> 0.0; foe.fainted || foe.hpFraction <= 0.0 -> 1.0
            else -> (first * q + (1.0 - first) * (1.0 - r) * q + (1.0 - q) * (1.0 - r) * tailWin).coerceIn(0.0, 1.0) }
        val hp = if (ownTurns.isFinite()) (own.hpFraction - e * (ownTurns - first).coerceAtLeast(0.0)).coerceIn(0.0, own.hpFraction) else own.hpFraction
        val otherHp = if (foeTurns.isFinite()) (foe.hpFraction - d * (foeTurns - (1.0 - first)).coerceAtLeast(0.0)).coerceIn(0.0, foe.hpFraction) else foe.hpFraction
        return PokemonMatchupScore(own.battlePokemonId, foe.battlePokemonId, field, mine, enemy, first, win, hp, otherHp,
            win * (.5 + .5 * hp) - (1.0 - win) * (.5 + .5 * otherHp))
    }
}
