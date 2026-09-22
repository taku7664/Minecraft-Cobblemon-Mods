package jbro.cobblemon.battleui.extended.pokemon.stats

import net.minecraft.text.Text

/**
 * Pure stat calculation utilities shared by speed calculator, tooltip builder,
 * and stat display. No mutable state.
 */
object StatCalculator {

    /**
     * Get the stat stage multiplier for a given stage (-6 to +6).
     * Uses the standard Pokemon formula: (2 + stage) / 2 for positive, 2 / (2 - stage) for negative.
     */
    fun getStageMultiplier(stage: Int): Double {
        return if (stage >= 0) {
            (2.0 + stage) / 2.0
        } else {
            2.0 / (2.0 - stage)
        }
    }

    /**
     * Calculate a stat value using the standard Pokemon formula.
     * stat = floor((floor((2 * base + iv + floor(ev/4)) * level / 100) + 5) * nature)
     */
    fun calculateStat(base: Int, level: Int, iv: Int, ev: Int, natureMod: Double): Int {
        val inner = ((2 * base + iv + ev / 4) * level / 100) + 5
        return (inner * natureMod).toInt()
    }

    /**
     * Apply a stat stage multiplier to a base value.
     */
    fun applyStageMultiplier(baseValue: Int, stage: Int): Int {
        return (baseValue * getStageMultiplier(stage)).toInt()
    }

    /**
     * Normalize ability name for comparison (lowercase, no spaces/underscores).
     */
    fun normalizeAbilityName(name: String?): String? = normalizeKnownName(
        name,
        setOf("chlorophyll", "swiftswim", "sandrush", "slushrush", "surgesurfer", "quickfeet", "unburden"),
        "cobblemon.ability."
    )

    // ═════════════════════════════════════════════════════════════════════════
    // Item-based Stat Multipliers
    // ═════════════════════════════════════════════════════════════════════════

    fun getItemSpeedMultiplier(itemName: String?): Double {
        val normalizedItem = normalizeKnownName(
            itemName, setOf("choice_scarf", "iron_ball"), "item.cobblemon."
        ) ?: return 1.0
        return when (normalizedItem) {
            "choice_scarf" -> 1.5
            "iron_ball" -> 0.5
            else -> 1.0
        }
    }

    private fun normalizeKnownName(name: String?, ids: Set<String>, translationPrefix: String): String? {
        if (name == null) return null
        val compact = name.lowercase().filter(Char::isLetterOrDigit)
        for (id in ids) {
            if (compact == id.filter(Char::isLetterOrDigit)) return id
            val translated = Text.translatable("$translationPrefix$id").string
            if (!translated.startsWith(translationPrefix) &&
                compact == translated.lowercase().filter(Char::isLetterOrDigit)) return id
        }
        return compact
    }
}
