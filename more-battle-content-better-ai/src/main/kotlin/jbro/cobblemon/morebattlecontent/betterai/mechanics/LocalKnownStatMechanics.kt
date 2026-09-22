package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Applies deterministic power and stat modifiers whose item or ability is public. */
internal object LocalKnownStatMechanics {
    fun effectivePower(basePower: Int, actor: BattlePokemonStateView): Int {
        if (canonical(actor.knownAbilityId) == "technician" && basePower <= 60) {
            return (basePower * 1.5).toInt().coerceAtLeast(1)
        }
        return basePower
    }

    /**
     * The attacking stat after items the battle has made public.
     *
     * Held items were almost entirely absent from the damage path - only the Utility Umbrella reached
     * it, through the weather - while the battle tower hands them out on nearly every set. A Choice
     * Band is half again the attack it is computed from, and an AI that cannot see its own band is
     * wrong about its own damage, which is the number every other judgement is derived from.
     *
     * Only a revealed item counts, which for the opponent means one already seen. The AI's own items
     * are never hidden from it, so this is mostly the trainer learning what it is actually holding.
     */
    fun attack(
        value: BattleIntegerRange,
        category: BattleMoveDamageCategory,
        actor: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange = scale(
        value,
        if (LocalPublicFieldMechanics.magicRoomActive(state)) 1.0 else attackMultiplier(category, actor),
    )

    private fun attackMultiplier(
        category: BattleMoveDamageCategory,
        actor: BattlePokemonStateView,
    ): Double = when (canonical(actor.knownHeldItemId)) {
        "choiceband" -> if (category == BattleMoveDamageCategory.PHYSICAL) 1.5 else 1.0
        "choicespecs" -> if (category == BattleMoveDamageCategory.SPECIAL) 1.5 else 1.0
        "lightball" -> if (canonical(actor.speciesId) == "pikachu") 2.0 else 1.0
        else -> 1.0
    }

    fun defence(
        value: BattleIntegerRange,
        stat: LocalPublicMoveDamageInputs.CombatStat,
        target: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange = scale(
        value,
        if (LocalPublicFieldMechanics.magicRoomActive(state)) 1.0 else defenceMultiplier(stat, target),
    )

    fun offensiveDefence(
        value: BattleIntegerRange,
        stat: LocalPublicMoveDamageInputs.CombatStat,
        pokemon: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange = defence(value, stat, pokemon, state)

    private fun defenceMultiplier(
        stat: LocalPublicMoveDamageInputs.CombatStat,
        target: BattlePokemonStateView,
    ): Double {
        val item = canonical(target.knownHeldItemId)
        val vest = if (
            stat == LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE && item == "assaultvest"
        ) 1.5 else 1.0
        // Eviolite needs to know the holder can still evolve, which the public state does not say. It
        // is left out rather than guessed: over-stating a defence makes the AI decline attacks that
        // would have worked, which is the more damaging way to be wrong.
        return vest
    }

    /**
     * Damage multipliers that apply after the stats, from items the battle has made public.
     *
     * Life Orb and Expert Belt scale the finished damage rather than a stat, so they are returned
     * separately and applied where the projection lands.
     */
    fun damageMultiplier(
        actor: BattlePokemonStateView,
        typeChartMultiplier: Double?,
        state: BattleStateView,
    ): Double = if (LocalPublicFieldMechanics.magicRoomActive(state)) 1.0 else
        when (canonical(actor.knownHeldItemId)) {
            "lifeorb" -> 1.3
            "expertbelt" -> if ((typeChartMultiplier ?: 1.0) > 1.0) 1.2 else 1.0
            else -> 1.0
        }

    /** Speed after a Choice Scarf, which is public once the item has been seen. */
    fun speed(
        value: BattleIntegerRange,
        pokemon: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange = scale(
        value,
        if (!LocalPublicFieldMechanics.magicRoomActive(state) &&
            canonical(pokemon.knownHeldItemId) == "choicescarf"
        ) 1.5 else 1.0,
    )

    private fun scale(value: BattleIntegerRange, multiplier: Double) = BattleIntegerRange(
        minimum = (value.minimum * multiplier).toInt().coerceAtLeast(1),
        maximum = (value.maximum * multiplier).toInt().coerceAtLeast(1),
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
}
