package jbro.cobblemon.mcc.internal.ai

import java.util.Locale

/**
 * What a non-damaging, non-setup move does to the battle.
 *
 * Refines [BattleOpponentMoveGroup.STATUS_OTHER]. A Boss reads the categories of the opponent's hidden
 * status moves (not their names), and the local Brain then picks a concrete move inside each category.
 */
enum class BattleStatusMoveCategory {
    /** Restores the user's HP or cures its team: Recover, Roost, Wish, Heal Bell. */
    RECOVERY,
    /** Blocks incoming moves for the turn: Protect, Detect, King's Shield. */
    PROTECTION,
    /** Gives the target a major status or a damaging volatile: Spore, Will-O-Wisp, Leech Seed. */
    STATUS_INFLICTION,
    /** Restricts or weakens the target: Taunt, Encore, Trick, Roar, stat drops. */
    DISRUPTION,
    /** Changes weather, terrain, rooms or the user's own side: Tailwind, Trick Room, Reflect. */
    FIELD,
    /** Lays an entry hazard on the target's side: Stealth Rock, Spikes, Sticky Web. */
    HAZARD,
    /** Every other status move: Substitute, Destiny Bond, Perish Song. */
    OTHER_STATUS,
}

object BattleStatusMoveCategories {
    /**
     * Returns the category of a status move that is not a pure self stat setup, or null for damaging
     * moves and pure setup, which already have their own groups.
     *
     * Declared Showdown effects decide first. Moves whose healing or disruption lives only in a
     * callback, and so never reaches the declarative facts, are listed by ID.
     */
    @JvmStatic
    fun classify(moveId: String, details: BattleMoveCandidateView): BattleStatusMoveCategory? {
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return null
        if (isPureSelfSetup(details)) return null
        val id = canonical(moveId)
        val effects = details.effects?.effects.orEmpty()
        fun has(kind: BattleMoveEffectKind, vararg targets: BattleMoveEffectTarget) =
            effects.any { it.kind == kind && (targets.isEmpty() || it.target in targets) }

        return when {
            // Declared as a target volatile but applied to an ally; it supports, it does not disrupt.
            id in ALLY_SUPPORT -> BattleStatusMoveCategory.OTHER_STATUS
            id in TEAM_PROTECTION || has(BattleMoveEffectKind.PROTECT_USER) -> BattleStatusMoveCategory.PROTECTION
            id in CALLBACK_RECOVERY || has(BattleMoveEffectKind.HEAL_FRACTION, BattleMoveEffectTarget.USER) ||
                effects.any { it.kind == BattleMoveEffectKind.SLOT_CONDITION && canonical(it.valueId.orEmpty()) == "wish" } ->
                BattleStatusMoveCategory.RECOVERY
            id in CALLBACK_STATUS_INFLICTION ||
                effects.any { it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET } ||
                effects.any {
                    it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                        canonical(it.valueId.orEmpty()) in INFLICTED_VOLATILES
                } -> BattleStatusMoveCategory.STATUS_INFLICTION
            has(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.TARGET_SIDE) -> BattleStatusMoveCategory.HAZARD
            has(BattleMoveEffectKind.WEATHER) || has(BattleMoveEffectKind.TERRAIN) ||
                has(BattleMoveEffectKind.FIELD_CONDITION) ||
                has(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.USER_SIDE) -> BattleStatusMoveCategory.FIELD
            id in CALLBACK_DISRUPTION || has(BattleMoveEffectKind.SWITCH_TARGET) ||
                effects.any {
                    it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET
                } ||
                effects.any {
                    it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                        it.statStages.values.any { stage -> stage < 0 }
                } -> BattleStatusMoveCategory.DISRUPTION
            else -> BattleStatusMoveCategory.OTHER_STATUS
        }
    }

    /** The same pure setup definition the move inference uses for [BattleOpponentMoveGroup.PURE_SETUP]. */
    @JvmStatic
    fun isPureSelfSetup(details: BattleMoveCandidateView): Boolean {
        val effects = details.effects?.effects.orEmpty()
        return effects.isNotEmpty() && effects.all { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.isNotEmpty()
        } && effects.any { effect -> effect.statStages.values.any { it > 0 } }
    }

    private fun canonical(value: String): String =
        PublicIds.canonical(value)

    /** Healing computed in Showdown callbacks, so it has no declarative `heal` fraction. */
    private val CALLBACK_RECOVERY = setOf(
        "synthesis", "morningsun", "moonlight", "shoreup", "rest", "painsplit", "strengthsap",
        "healbell", "aromatherapy", "junglehealing", "lunarblessing", "purify", "swallow",
        "refresh", "aquaring", "ingrain",
    )

    private val ALLY_SUPPORT = setOf("helpinghand", "dragoncheer")

    /** Side-wide shields that block moves for the turn like Protect does. */
    private val TEAM_PROTECTION = setOf("wideguard", "quickguard", "craftyshield", "matblock")

    private val CALLBACK_STATUS_INFLICTION = setOf("psychoshift")

    /** Target volatiles that hurt or disable over time, rather than only restricting move choice. */
    private val INFLICTED_VOLATILES = setOf("confusion", "leechseed", "yawn", "attract", "curse", "nightmare", "saltcure")

    /** Disruption implemented in callbacks: item swaps, stat resets and move copying denial. */
    private val CALLBACK_DISRUPTION = setOf(
        "trick", "switcheroo", "haze", "spite", "topsyturvy", "roleplay", "skillswap", "entrainment",
        "worryseed", "simplebeam", "gastroacid", "soak", "magicpowder", "defog", "courtchange", "partingshot",
        "meanlook", "block", "spiderweb", "imprison", "trickortreat", "forestscurse", "corrosivegas", "venomdrench",
    )
}
