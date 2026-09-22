package jbro.cobblemon.morebattlecontent.betterai.mechanics

internal object StandardTypeEffectiveness {
    fun multiplier(
        attackingTypeId: String,
        defendingTypeIds: Set<String>,
        ignoreTypeImmunity: Boolean = false,
    ): Double {
        val chart = ATTACK_MULTIPLIERS[canonical(attackingTypeId)] ?: return 1.0
        return defendingTypeIds.fold(1.0) { result, defendingTypeId ->
            val contribution = chart[canonical(defendingTypeId)] ?: 1.0
            result * if (ignoreTypeImmunity && contribution == 0.0) 1.0 else contribution
        }
    }

    /** Move-specific chart overrides that Showdown exposes through `onEffectiveness`. */
    fun multiplierForMove(
        moveId: String?,
        attackingTypeId: String,
        defendingTypeIds: Set<String>,
        ignoreTypeImmunity: Boolean = false,
    ): Double {
        if (canonical(moveId) != FREEZE_DRY || canonical(attackingTypeId) != "ice") {
            return multiplier(attackingTypeId, defendingTypeIds, ignoreTypeImmunity)
        }
        val chart = ATTACK_MULTIPLIERS.getValue("ice")
        return defendingTypeIds.fold(1.0) { result, defendingTypeId ->
            val defendingType = canonical(defendingTypeId)
            val contribution = if (defendingType == "water") 2.0 else chart[defendingType] ?: 1.0
            result * if (ignoreTypeImmunity && contribution == 0.0) 1.0 else contribution
        }
    }

    /**
     * Type-chart multiplier adjusted only for a public ability that nullifies the hit.
     *
     * Only abilities the defender has actually revealed are applied, so this stays inside the fair
     * information policy: `knownAbilityId` is null until the battle exposes it. Without this the AI
     * happily aims Ground moves at a revealed Levitate and Fire moves at a revealed Flash Fire,
     * because the raw chart says they connect.
     *
     * Damage-reducing abilities stay out of this value. They are final damage modifiers, not type
     * chart cells, and [LocalPublicMechanicsKernel] applies them exactly once after base damage.
     */
    fun multiplierAgainst(
        attackingTypeId: String,
        defendingTypeIds: Set<String>,
        defenderAbilityId: String?,
        ignoreTypeImmunity: Boolean = false,
        applyAbilities: Boolean = true,
        moveId: String? = null,
    ): Double {
        val base = multiplierForMove(moveId, attackingTypeId, defendingTypeIds, ignoreTypeImmunity)
        if (!applyAbilities) return base
        val ability = canonical(defenderAbilityId) ?: return base
        val moveType = attackingTypeId.substringAfter(':').lowercase()
        if (!ignoreTypeImmunity && ABSORBING_ABILITIES[ability]?.contains(moveType) == true) return 0.0
        if (ability == "wonderguard" && base < 2.0) return 0.0
        return base
    }

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
        ?.takeIf(String::isNotEmpty)

    private const val FREEZE_DRY = "freezedry"

    /** Revealed abilities that nullify a whole attacking type. */
    private val ABSORBING_ABILITIES: Map<String, Set<String>> = mapOf(
        "levitate" to setOf("ground"),
        "flashfire" to setOf("fire"),
        "waterabsorb" to setOf("water"),
        "dryskin" to setOf("water"),
        "stormdrain" to setOf("water"),
        "voltabsorb" to setOf("electric"),
        "lightningrod" to setOf("electric"),
        "motordrive" to setOf("electric"),
        "sapsipper" to setOf("grass"),
        "eartheater" to setOf("ground"),
        "windrider" to setOf("flying"),
    )

    private val ATTACK_MULTIPLIERS: Map<String, Map<String, Double>> = mapOf(
        "normal" to mapOf("rock" to 0.5, "ghost" to 0.0, "steel" to 0.5),
        "fire" to mapOf(
            "fire" to 0.5, "water" to 0.5, "grass" to 2.0, "ice" to 2.0,
            "bug" to 2.0, "rock" to 0.5, "dragon" to 0.5, "steel" to 2.0,
        ),
        "water" to mapOf(
            "fire" to 2.0, "water" to 0.5, "grass" to 0.5, "ground" to 2.0,
            "rock" to 2.0, "dragon" to 0.5,
        ),
        "electric" to mapOf(
            "water" to 2.0, "electric" to 0.5, "grass" to 0.5, "ground" to 0.0,
            "flying" to 2.0, "dragon" to 0.5,
        ),
        "grass" to mapOf(
            "fire" to 0.5, "water" to 2.0, "grass" to 0.5, "ice" to 0.5, "poison" to 0.5,
            "ground" to 2.0, "flying" to 0.5, "bug" to 0.5, "rock" to 2.0,
            "dragon" to 0.5, "steel" to 0.5,
        ),
        "ice" to mapOf(
            "fire" to 0.5, "water" to 0.5, "grass" to 2.0, "ice" to 0.5,
            "ground" to 2.0, "flying" to 2.0, "dragon" to 2.0, "steel" to 0.5,
        ),
        "fighting" to mapOf(
            "normal" to 2.0, "ice" to 2.0, "poison" to 0.5, "flying" to 0.5,
            "psychic" to 0.5, "bug" to 0.5, "rock" to 2.0, "ghost" to 0.0,
            "dark" to 2.0, "steel" to 2.0, "fairy" to 0.5,
        ),
        "poison" to mapOf(
            "grass" to 2.0, "poison" to 0.5, "ground" to 0.5, "rock" to 0.5,
            "ghost" to 0.5, "steel" to 0.0, "fairy" to 2.0,
        ),
        "ground" to mapOf(
            "fire" to 2.0, "electric" to 2.0, "grass" to 0.5, "poison" to 2.0,
            "flying" to 0.0, "bug" to 0.5, "rock" to 2.0, "steel" to 2.0,
        ),
        "flying" to mapOf(
            "electric" to 0.5, "grass" to 2.0, "fighting" to 2.0, "bug" to 2.0,
            "rock" to 0.5, "steel" to 0.5,
        ),
        "psychic" to mapOf(
            "fighting" to 2.0, "poison" to 2.0, "psychic" to 0.5,
            "dark" to 0.0, "steel" to 0.5,
        ),
        "bug" to mapOf(
            "fire" to 0.5, "grass" to 2.0, "fighting" to 0.5, "poison" to 0.5,
            "flying" to 0.5, "psychic" to 2.0, "ghost" to 0.5, "dark" to 2.0,
            "steel" to 0.5, "fairy" to 0.5,
        ),
        "rock" to mapOf(
            "fire" to 2.0, "ice" to 2.0, "fighting" to 0.5, "ground" to 0.5,
            "flying" to 2.0, "bug" to 2.0, "steel" to 0.5,
        ),
        "ghost" to mapOf(
            "normal" to 0.0, "psychic" to 2.0, "ghost" to 2.0, "dark" to 0.5,
        ),
        "dragon" to mapOf("dragon" to 2.0, "steel" to 0.5, "fairy" to 0.0),
        "dark" to mapOf(
            "fighting" to 0.5, "psychic" to 2.0, "ghost" to 2.0,
            "dark" to 0.5, "fairy" to 0.5,
        ),
        "steel" to mapOf(
            "fire" to 0.5, "water" to 0.5, "electric" to 0.5, "ice" to 2.0,
            "rock" to 2.0, "steel" to 0.5, "fairy" to 2.0,
        ),
        "fairy" to mapOf(
            "fire" to 0.5, "fighting" to 2.0, "poison" to 0.5, "dragon" to 2.0,
            "dark" to 2.0, "steel" to 0.5,
        ),
    )
}
