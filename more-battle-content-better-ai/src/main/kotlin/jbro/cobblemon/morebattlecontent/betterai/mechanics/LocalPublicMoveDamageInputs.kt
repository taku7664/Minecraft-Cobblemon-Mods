package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView

/** Move-specific damage inputs that are completely determined by the public battle state. */
internal object LocalPublicMoveDamageInputs {
    enum class OffensiveStat { ATTACK, DEFENCE, SPECIAL_ATTACK }

    data class Resolution(
        val power: Int,
        val offensivePokemon: BattlePokemonStateView,
        val offensiveStat: OffensiveStat,
        val offensiveStage: Int,
    )

    fun resolve(
        moveId: String?,
        templatePower: Double,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        special: Boolean,
    ): Resolution? {
        val id = canonical(moveId)
        if (id in UNRESOLVED_DYNAMIC_POWER_MOVES) return null
        val wholePower = templatePower.toInt().takeIf { it > 0 && it.toDouble() == templatePower } ?: return null
        val power = when (id) {
            "storedpower", "powertrip" -> wholePower + 20 * actor.positiveBoosts()
            "facade" -> if (actor.statusId != null) wholePower * 2 else wholePower
            "hex", "infernalparade" -> if (target.statusId != null) wholePower * 2 else wholePower
            "brine" -> if (target.hpFraction <= 0.5) wholePower * 2 else wholePower
            "venoshock", "barbbarrage" -> if (canonical(target.statusId) in POISON_STATUSES) wholePower * 2 else wholePower
            "smellingsalts" -> if (canonical(target.statusId) in PARALYSIS_STATUSES) wholePower * 2 else wholePower
            "wakeupslap" -> if (canonical(target.statusId) in SLEEP_STATUSES) wholePower * 2 else wholePower
            else -> wholePower
        }
        val offensivePokemon = if (id == "foulplay") target else actor
        val offensiveStat = when {
            id == "bodypress" -> OffensiveStat.DEFENCE
            special -> OffensiveStat.SPECIAL_ATTACK
            else -> OffensiveStat.ATTACK
        }
        val stage = when (offensiveStat) {
            OffensiveStat.ATTACK -> offensivePokemon.stage("attack", "atk")
            OffensiveStat.DEFENCE -> offensivePokemon.stage("defence", "defense", "def")
            OffensiveStat.SPECIAL_ATTACK -> offensivePokemon.stage("special_attack", "specialattack", "spa")
        }
        return Resolution(power, offensivePokemon, offensiveStat, stage)
    }

    private fun BattlePokemonStateView.positiveBoosts(): Int = statStages.values.sumOf { it.coerceAtLeast(0) }

    private fun BattlePokemonStateView.stage(vararg aliases: String): Int = statStages.entries
        .firstOrNull { (key, _) -> canonical(key) in aliases }
        ?.value
        ?.coerceIn(-6, 6)
        ?: 0

    private fun canonical(value: String?): String = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
        .orEmpty()

    private val POISON_STATUSES = setOf("psn", "poison", "poisoned", "tox", "toxic", "badlypoisoned")
    private val PARALYSIS_STATUSES = setOf("par", "paralysis", "paralyzed", "paralysed")
    private val SLEEP_STATUSES = setOf("slp", "sleep", "asleep")

    /** Public state does not currently carry the input needed to choose one exact power. */
    private val UNRESOLVED_DYNAMIC_POWER_MOVES = setOf(
        "assurance", "avalanche", "boltbeak", "crushgrip", "echoedvoice", "electroball",
        "eruption", "fishiousrend", "flail", "frustration", "furycutter", "gyroball",
        "heatcrash", "heavyslam", "iceball", "lastrespects", "lowkick", "magnitude", "payback",
        "present", "punishment", "ragefist", "return", "reversal", "revenge", "rollout",
        "round", "stompingtantrum", "trumpcard", "waterspout", "wringout",
    )
}
