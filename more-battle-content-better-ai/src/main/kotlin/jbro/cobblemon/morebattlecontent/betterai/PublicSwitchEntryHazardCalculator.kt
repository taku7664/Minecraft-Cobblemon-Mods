package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/** Calculates only publicly determined HP loss on switch-in. Non-HP entry effects stay unresolved. */
internal object PublicSwitchEntryHazardCalculator {
    fun hpLoss(
        field: BattleFieldStateView,
        enteringSide: BattleSide,
        pokemon: BattlePokemonStateView,
    ): Double? {
        val hazards = field.sideConditions.getValue(enteringSide)
        if (hazards.isEmpty()) return 0.0
        val item = canonical(pokemon.knownHeldItemId)
        val ability = canonical(pokemon.knownAbilityId)
        if (item == HEAVY_DUTY_BOOTS || ability == MAGIC_GUARD) return 0.0

        var loss = 0.0
        hazards.forEach { hazard ->
            when (canonical(hazard.effectId)) {
                STEALTH_ROCK -> {
                    if (pokemon.knownTypeIds.isEmpty()) return null
                    loss += STEALTH_ROCK_FRACTION *
                        StandardTypeEffectiveness.multiplier("rock", pokemon.knownTypeIds)
                }
                G_MAX_STEELSURGE -> {
                    if (pokemon.knownTypeIds.isEmpty()) return null
                    loss += STEALTH_ROCK_FRACTION *
                        StandardTypeEffectiveness.multiplier("steel", pokemon.knownTypeIds)
                }
                SPIKES -> {
                    if (pokemon.knownTypeIds.isEmpty()) return null
                    if (isGrounded(pokemon, item, ability)) {
                        loss += when ((hazard.stacks ?: 1).coerceIn(1, 3)) {
                            1 -> 1.0 / 8.0
                            2 -> 1.0 / 6.0
                            else -> 1.0 / 4.0
                        }
                    }
                }
            }
        }
        return loss.coerceIn(0.0, 1.0)
    }

    private fun isGrounded(pokemon: BattlePokemonStateView, item: String?, ability: String?): Boolean =
        pokemon.knownTypeIds.none { canonical(it) == FLYING } &&
            ability != LEVITATE && item != AIR_BALLOON

    private fun canonical(value: String?): String? = value?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

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
