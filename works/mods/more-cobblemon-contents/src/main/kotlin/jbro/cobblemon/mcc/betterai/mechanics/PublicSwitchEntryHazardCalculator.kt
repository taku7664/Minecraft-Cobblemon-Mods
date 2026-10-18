package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Calculates only publicly determined HP loss on switch-in. Non-HP entry effects stay unresolved. */
internal object PublicSwitchEntryHazardCalculator {
    fun hpLoss(
        state: BattleStateView,
        enteringSide: BattleSide,
        pokemon: BattlePokemonStateView,
    ): Double? = hpLoss(
        state.field,
        enteringSide,
        pokemon,
        activeItem = LocalPublicItemState.activeItemId(state, pokemon),
        grounded = LocalPublicTurnOrder.grounded(state, pokemon),
        ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon),
    )

    fun hpLoss(
        field: BattleFieldStateView,
        enteringSide: BattleSide,
        pokemon: BattlePokemonStateView,
    ): Double? = hpLoss(
        field,
        enteringSide,
        pokemon,
        activeItem = canonical(pokemon.knownHeldItemId),
        grounded = isGrounded(pokemon, canonical(pokemon.knownHeldItemId), canonical(pokemon.knownAbilityId)),
        ability = canonical(pokemon.knownAbilityId),
    )

    private fun hpLoss(
        field: BattleFieldStateView,
        enteringSide: BattleSide,
        pokemon: BattlePokemonStateView,
        activeItem: String?,
        grounded: Boolean,
        ability: String?,
    ): Double? {
        val hazards = field.sideConditions.getValue(enteringSide)
        if (hazards.isEmpty()) return 0.0
        if (activeItem == HEAVY_DUTY_BOOTS || ability == MAGIC_GUARD) return 0.0

        val fractions = ArrayList<Double>()
        fun hazard(fraction: Double) { fractions += fraction }
        hazards.forEach { hazard ->
            when (canonical(hazard.effectId)) {
                STEALTH_ROCK -> {
                    if (pokemon.knownTypeIds.isEmpty()) return null
                    hazard(STEALTH_ROCK_FRACTION *
                        StandardTypeEffectiveness.multiplier("rock", pokemon.knownTypeIds))
                }
                G_MAX_STEELSURGE -> {
                    if (pokemon.knownTypeIds.isEmpty()) return null
                    hazard(STEALTH_ROCK_FRACTION *
                        StandardTypeEffectiveness.multiplier("steel", pokemon.knownTypeIds))
                }
                SPIKES -> {
                    if (pokemon.knownTypeIds.isEmpty()) return null
                    if (grounded) {
                        hazard(when ((hazard.stacks ?: 1).coerceIn(1, 3)) {
                            1 -> 1.0 / 8.0
                            2 -> 1.0 / 6.0
                            else -> 1.0 / 4.0
                        })
                    }
                }
            }
        }
        return wholeHp(fractions, pokemon.combatStats?.maxHp).coerceIn(0.0, 1.0)
    }

    /**
     * The hazards' damage as Showdown deals it: each its own whole number of HP, rounded down and at least 1 (so two can
     * leave 1 HP where their fractions add up to the whole bar, and Shedinja falls to Stealth Rock). With the maximum HP
     * only a range, the least share of it over every value in the range, so a Pokemon on the boundary is not read as
     * fainting on entry (Codex reviews of fe443485 and cfe75d8e).
     */
    private fun wholeHp(fractions: List<Double>, range: jbro.cobblemon.mcc.internal.ai.BattleIntegerRange?): Double {
        val fractional = fractions.sum()
        if (fractions.isEmpty() || range == null || range.minimum <= 0) return fractional
        return (range.minimum..maxOf(range.minimum, range.maximum)).minOf { maxHp ->
            fractions.sumOf { fraction ->
                if (fraction <= 0.0) 0.0 else kotlin.math.floor(maxHp * fraction + 1e-9).coerceAtLeast(1.0)
            } / maxHp
        }
    }

    private fun isGrounded(pokemon: BattlePokemonStateView, item: String?, ability: String?): Boolean =
        pokemon.knownTypeIds.none { canonical(it) == FLYING } &&
            ability != LEVITATE && item != AIR_BALLOON

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private const val STEALTH_ROCK_FRACTION = 1.0 / 8.0
    private const val STEALTH_ROCK = "stealthrock"
    private const val SPIKES = "spikes"
    private const val G_MAX_STEELSURGE = "gmaxsteelsurge"
    private const val HEAVY_DUTY_BOOTS = "heavydutyboots"
    private const val AIR_BALLOON = "airballoon"
    private const val MAGIC_GUARD = "magicguard"
    private const val LEVITATE = "levitate"
    private const val FLYING = "flying"
}
