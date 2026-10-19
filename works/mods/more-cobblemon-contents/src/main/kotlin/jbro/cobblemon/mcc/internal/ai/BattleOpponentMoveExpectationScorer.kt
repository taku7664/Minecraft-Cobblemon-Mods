package jbro.cobblemon.mcc.internal.ai

import jbro.cobblemon.mcc.internal.ai.*

/**
 * Ranks a learnset attack as a set-level expectation, not as a turn-specific action choice.
 * All weights live here so inference policy and expectation balance remain separate.
 */
internal object BattleOpponentMoveExpectationScorer {
    fun score(
        pokemon: BattlePokemonStateView,
        move: BattleMoveCandidateView,
        format: BattleFormat,
        /** The move's id, for what only its id says (Focus Punch, Tera Blast); see [BattleMoveSetExpectation]. */
        moveId: String? = null,
        /** The board's reading of the move's own stat changes; without it, the set-level values below. */
        ownStages: BattleOwnStageValue? = null,
    ): Double {
        if (move.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val conditional = BattleMoveSetExpectation.factor(moveId, move)
        if (conditional <= 0.0) return 0.0

        val hitProbability = (move.accuracy / 100.0).coerceIn(0.0, 1.0)
        val sameTypeBonus = BattlePublicStabRules.conservativeMultiplier(
            pokemon.knownBaseStabTypeIds,
            pokemon.knownTeraTypeId,
            pokemon.knownStellarBoostedTypeIds,
            BattleMoveSetExpectation.typeId(moveId, move, pokemon),
        ) ?: 1.0
        // A multi-hit attack (Dual Wingbeat, Rock Blast) at its average hits, as LocalMoveThreatScore reads it: at one hit,
        // a Roark Aerodactyl that carried Dual Wingbeat read as carrying Sky Attack (2026-10-18).
        val hits = move.effects?.effects.orEmpty().firstOrNull { it.kind == BattleMoveEffectKind.MULTI_HIT }
            ?.amountRange?.let(BattleMoveSetExpectation::averageHits) ?: 1.0
        val baseDamage = move.power * hits * hitProbability * sameTypeBonus *
            categoryFit(pokemon, move) * spreadValue(move.targetPattern, format)
        // Its own stat changes as the board reads them: the share of its damage it gains or loses against the Pokemon it
        // will face. The flat 50 a raised stage read Scale Shot's Speed the same whether or not it turned any exchange.
        val board = if (moveId != null && move.effects?.effects.orEmpty().any(::isOwnStatChange)) {
            ownStages?.worthChange(pokemon, moveId, move)
        } else null
        val secondaryValue = move.effects?.effects.orEmpty().sumOf { effect ->
            val value = when {
                isOwnStatChange(effect) -> if (board != null) 0.0 else ownRaiseValue(effect)
                isTargetStatChange(effect) -> baseDamage * targetDropShare(effect)
                else -> effectValue(effect)
            }
            value * (effect.probability ?: 1.0) * hitProbability
        }
        val ownDropCost = if (board != null) -baseDamage * board else move.effects?.effects.orEmpty().filter(::isOwnStatChange).sumOf { effect ->
            baseDamage * ownDropShare(effect, move.damageCategory) * (effect.probability ?: 1.0)
        }
        // The user's own HP a move spends costs that share of its value, every kind alike: a share of the damage dealt
        // (Double-Edge, Brave Bird), of its maximum HP (Steel Beam, Mind Blown, Chloroblast, Struggle), or half of it on a
        // miss (High Jump Kick). Flat amounts (Steel Beam -30, Double-Edge -20) read both over Flash Cannon and Body Slam
        // (user decision 2026-10-18).
        // A publicly known Rock Head or Magic Guard spends none (BattleRecoilImmunity).
        val ability = pokemon.knownAbilityId?.let(PublicIds::canonical)
        val maxHpCost = move.effects?.effects.orEmpty().filterNot { BattleRecoilImmunity.blocks(it, ability) }.sumOf { effect ->
            val share = effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 }
            val chance = effect.probability ?: 1.0
            when (effect.kind) {
                BattleMoveEffectKind.RECOIL_FRACTION -> baseDamage * (share ?: DEFAULT_RECOIL_FRACTION) * chance
                BattleMoveEffectKind.MAX_HP_RECOIL, BattleMoveEffectKind.STRUGGLE_RECOIL ->
                    baseDamage * (share ?: DEFAULT_MAX_HP_RECOIL) * chance
                BattleMoveEffectKind.CRASH_RECOIL -> baseDamage * (1.0 - hitProbability) * CRASH_SHARE
                else -> 0.0
            }
        }
        val tempoPenalty = move.effects?.effects.orEmpty().sumOf { effect ->
            when (effect.kind) {
                BattleMoveEffectKind.CHARGE_TURN -> baseDamage * CHARGE_PENALTY
                BattleMoveEffectKind.RECHARGE_TURN -> baseDamage * RECHARGE_PENALTY
                BattleMoveEffectKind.SELF_DESTRUCT -> baseDamage * SELF_DESTRUCT_PENALTY
                else -> 0.0
            }
        }
        return (baseDamage + secondaryValue - tempoPenalty - ownDropCost - maxHpCost + move.priority.coerceAtLeast(0) * PRIORITY_WEIGHT) * conditional
    }

    private fun categoryFit(
        pokemon: BattlePokemonStateView,
        move: BattleMoveCandidateView,
    ): Double {
        val category = move.damageCategory
        val stats = pokemon.combatStats ?: return 1.0
        val attack = stats.attack.midpoint()
        val specialAttack = stats.specialAttack.midpoint()
        // A move that hits with another of its user's stats (Body Press with Defence) is read at that stat. Read at
        // Attack, a Corviknight's Body Press fell behind Body Slam and Double-Edge, and a Kingambit set up Swords Dance
        // into it and fell to one (2026-10-20).
        val override = move.effects?.mechanicFlags.orEmpty().firstOrNull { it.startsWith(OVERRIDE_OFFENSIVE_STAT) }
            ?.removePrefix(OVERRIDE_OFFENSIVE_STAT)?.let { stat ->
                when (stat) {
                    "defence" -> stats.defence.midpoint()
                    "special_defence" -> stats.specialDefence.midpoint()
                    "attack" -> attack
                    "special_attack" -> specialAttack
                    else -> null
                }
            }
        val strongest = maxOf(attack, specialAttack, override ?: 0.0).coerceAtLeast(1.0)
        if (override != null) return (override / strongest).coerceIn(MINIMUM_CATEGORY_FIT, 1.0) * (override / maxOf(attack, specialAttack).coerceAtLeast(1.0)).coerceAtLeast(1.0)
        val selected = when (category) {
            BattleMoveDamageCategory.PHYSICAL -> attack
            BattleMoveDamageCategory.SPECIAL -> specialAttack
            BattleMoveDamageCategory.STATUS -> strongest
        }
        return (selected / strongest).coerceIn(MINIMUM_CATEGORY_FIT, 1.0)
    }

    private const val OVERRIDE_OFFENSIVE_STAT = "override_offensive_stat:"

    private fun spreadValue(pattern: BattleMoveTargetPattern, format: BattleFormat): Double =
        if (format == BattleFormat.DOUBLE && pattern == BattleMoveTargetPattern.ALL_OPPONENTS) {
            DOUBLES_ALL_OPPONENTS_VALUE
        } else {
            1.0
        }

    private fun effectValue(effect: BattleMoveEffectView): Double = when (effect.kind) {
        BattleMoveEffectKind.STAT_STAGE -> stageValue(effect)
        BattleMoveEffectKind.STATUS -> directionalValue(effect.target, STATUS_VALUE)
        BattleMoveEffectKind.VOLATILE_STATUS -> directionalValue(effect.target, VOLATILE_VALUE)
        BattleMoveEffectKind.HEAL_FRACTION ->
            recoveryValue(effect.target, effect.fractionRange?.midpoint().orZero() * HEAL_FRACTION_VALUE)
        BattleMoveEffectKind.DRAIN_FRACTION ->
            effect.fractionRange?.midpoint().orZero() * DRAIN_FRACTION_VALUE
        BattleMoveEffectKind.SWITCH_TARGET -> directionalValue(effect.target, FORCED_SWITCH_VALUE)
        BattleMoveEffectKind.SWITCH_USER -> PIVOT_VALUE
        else -> 0.0
    }

    private fun isTargetStatChange(effect: BattleMoveEffectView): Boolean =
        effect.kind == BattleMoveEffectKind.STAT_STAGE &&
            (effect.target == BattleMoveEffectTarget.SELECTED_TARGET || effect.target == BattleMoveEffectTarget.TARGET_SIDE)

    /**
     * A drop on the target, [TARGET_STAGE_SHARE] of the move's damage a stage (a raise on it costs the same). A flat 50
     * a stage put a 60-power Bulldoze and a 55-power Snarl above a physical Arcanine's Close Combat, and the hypotheses
     * left that out (user decision, 2026-10-18; LocalMoveThreatScore's rate).
     */
    private fun targetDropShare(effect: BattleMoveEffectView): Double = -effect.statStages.values.sum() * TARGET_STAGE_SHARE

    private fun isOwnStatChange(effect: BattleMoveEffectView): Boolean =
        effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER

    /** A raise on the user keeps the flat stage value; its drops are [ownDropShare]. */
    private fun ownRaiseValue(effect: BattleMoveEffectView): Double =
        effect.statStages.values.filter { it > 0 }.sum() * STAT_STAGE_VALUE

    /**
     * The share of the move's damage its user's own drops cost, for whether a set carries it (user decision,
     * 2026-10-18). A flat 50 a stage read Close Combat at 120 - 100 = 20, below Hyper Voice and Snarl on a physical
     * Arcanine, so the hypotheses left out the move that then knocked out a Duraludon switching in. The stat the move
     * attacks with: at its real stage multiplier, averaged over two uses (Superpower's Attack -1 keeps 5/6, Draco
     * Meteor's -2 3/4). Defense, Special Defense, Speed and the stat it does not attack with: [OTHER_DROP_SHARE] a
     * stage, since what they cost depends on the Pokemon it faces, which this set-level score does not see.
     */
    private fun ownDropShare(effect: BattleMoveEffectView, category: BattleMoveDamageCategory): Double {
        val attacking = when (category) {
            BattleMoveDamageCategory.PHYSICAL -> ATTACK
            BattleMoveDamageCategory.SPECIAL -> SPECIAL_ATTACK
            BattleMoveDamageCategory.STATUS -> null
        }
        return effect.statStages.entries.filter { it.value < 0 }.sumOf { (stat, stages) ->
            val dropped = -stages
            if (statKey(stat) == attacking) 1.0 - (1.0 + 2.0 / (2.0 + dropped)) / 2.0 else dropped * OTHER_DROP_SHARE
        }.coerceAtMost(1.0)
    }

    private fun statKey(stat: String): String = when (stat.lowercase().filter(Char::isLetterOrDigit)) {
        "atk", "attack" -> ATTACK
        "spa", "specialattack" -> SPECIAL_ATTACK
        else -> stat.lowercase()
    }

    private fun stageValue(effect: BattleMoveEffectView): Double {
        val signedStages = effect.statStages.values.sum()
        return when (effect.target) {
            BattleMoveEffectTarget.USER,
            BattleMoveEffectTarget.USER_SIDE,
            -> signedStages * STAT_STAGE_VALUE
            BattleMoveEffectTarget.SELECTED_TARGET,
            BattleMoveEffectTarget.TARGET_SIDE,
            -> -signedStages * STAT_STAGE_VALUE
            BattleMoveEffectTarget.FIELD -> 0.0
        }
    }

    private fun directionalValue(target: BattleMoveEffectTarget, magnitude: Double): Double = when (target) {
        BattleMoveEffectTarget.USER,
        BattleMoveEffectTarget.USER_SIDE,
        -> -magnitude
        BattleMoveEffectTarget.SELECTED_TARGET,
        BattleMoveEffectTarget.TARGET_SIDE,
        -> magnitude
        BattleMoveEffectTarget.FIELD -> 0.0
    }

    private fun recoveryValue(target: BattleMoveEffectTarget, magnitude: Double): Double = when (target) {
        BattleMoveEffectTarget.USER,
        BattleMoveEffectTarget.USER_SIDE,
        -> magnitude
        BattleMoveEffectTarget.SELECTED_TARGET,
        BattleMoveEffectTarget.TARGET_SIDE,
        -> -magnitude
        BattleMoveEffectTarget.FIELD -> 0.0
    }

    private fun BattleIntegerRange.midpoint(): Double = (minimum + maximum) / 2.0
    private fun BattleFractionRange.midpoint(): Double = (minimum + maximum) / 2.0
    private fun Double?.orZero(): Double = this ?: 0.0
    private fun Double?.orDefault(default: Double): Double = this ?: default
    private const val MINIMUM_CATEGORY_FIT = 0.65
    /** Each stage of a user drop on a stat the move does not attack with. */
    private const val OTHER_DROP_SHARE = 0.05
    /** Each stage a damaging move drops on its target. */
    private const val TARGET_STAGE_SHARE = 0.10
    private const val ATTACK = "attack"
    private const val SPECIAL_ATTACK = "specialattack"
    /** Two targets at the native 0.75 spread modifier. */
    private const val DOUBLES_ALL_OPPONENTS_VALUE = 1.5
    private const val PRIORITY_WEIGHT = 12.0
    private const val STAT_STAGE_VALUE = 50.0
    private const val STATUS_VALUE = 40.0
    private const val VOLATILE_VALUE = 20.0
    private const val HEAL_FRACTION_VALUE = 40.0
    private const val DRAIN_FRACTION_VALUE = 25.0
    /** High Jump Kick and its kind cost half the user's maximum HP on a miss. */
    private const val CRASH_SHARE = 0.5
    private const val DEFAULT_RECOIL_FRACTION = 1.0 / 3.0
    private const val DEFAULT_MAX_HP_RECOIL = 0.5
    private const val FORCED_SWITCH_VALUE = 15.0
    private const val PIVOT_VALUE = 8.0
    private const val CHARGE_PENALTY = 0.50
    private const val RECHARGE_PENALTY = 0.50
    private const val SELF_DESTRUCT_PENALTY = 0.80
}
