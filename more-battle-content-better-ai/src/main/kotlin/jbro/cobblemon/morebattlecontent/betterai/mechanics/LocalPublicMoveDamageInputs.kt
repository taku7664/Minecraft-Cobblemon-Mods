package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Move-specific damage inputs that are completely determined by the public battle state. */
internal object LocalPublicMoveDamageInputs {
    enum class CombatStat { ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE }

    data class Resolution(
        val power: Int,
        val offensivePokemon: BattlePokemonStateView,
        val offensiveStat: CombatStat,
        val offensiveStage: Int,
        val defensiveStat: CombatStat,
        val defensiveStage: Int,
    )

    fun resolve(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        state: BattleStateView,
    ): Resolution? {
        val details = candidate.moveDetails ?: return null
        val id = canonical(candidate.moveId)
        if (isUnresolvedDynamicDamage(candidate)) return null
        val wholePower = details.power.toInt().takeIf { it > 0 && it.toDouble() == details.power } ?: return null
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
        val offensiveStat = (
            overrideStat(details, "override_offensive_stat") ?: when {
                id == "bodypress" -> CombatStat.DEFENCE
                details.damageCategory == BattleMoveDamageCategory.SPECIAL -> CombatStat.SPECIAL_ATTACK
                else -> CombatStat.ATTACK
            }
            ).swapDefencesIfWonderRoom(state)
        val defensiveStat = (
            overrideStat(details, "override_defensive_stat") ?: when (details.damageCategory) {
                BattleMoveDamageCategory.PHYSICAL -> CombatStat.DEFENCE
                BattleMoveDamageCategory.SPECIAL -> CombatStat.SPECIAL_DEFENCE
                BattleMoveDamageCategory.STATUS -> return null
            }
            ).swapDefencesIfWonderRoom(state)
        return Resolution(
            power = power,
            offensivePokemon = offensivePokemon,
            offensiveStat = offensiveStat,
            offensiveStage = offensivePokemon.stage(offensiveStat),
            defensiveStat = defensiveStat,
            defensiveStage = target.stage(defensiveStat),
        )
    }

    /** True when the public model knows the template value is not the move's resolved damage input. */
    fun isUnresolvedDynamicDamage(candidate: BattleActionCandidate): Boolean {
        val id = canonical(candidate.moveId)
        if (id in PUBLICLY_RESOLVED_DYNAMIC_MOVES) return false
        if (id in LEGACY_UNRESOLVED_DYNAMIC_MOVES) return true
        val flags = candidate.moveDetails?.effects?.mechanicFlags.orEmpty()
        return flags.any { it in DYNAMIC_DAMAGE_FLAGS }
    }

    private fun overrideStat(details: BattleMoveCandidateView, prefix: String): CombatStat? =
        details.effects?.mechanicFlags.orEmpty().firstNotNullOfOrNull { flag ->
            if (!flag.startsWith("$prefix:")) null else when (flag.substringAfter(':')) {
                "attack" -> CombatStat.ATTACK
                "defence" -> CombatStat.DEFENCE
                "special_attack" -> CombatStat.SPECIAL_ATTACK
                "special_defence" -> CombatStat.SPECIAL_DEFENCE
                else -> null
            }
        }

    private fun CombatStat.swapDefencesIfWonderRoom(state: BattleStateView): CombatStat {
        if (!LocalPublicFieldMechanics.wonderRoomActive(state)) return this
        return when (this) {
            CombatStat.DEFENCE -> CombatStat.SPECIAL_DEFENCE
            CombatStat.SPECIAL_DEFENCE -> CombatStat.DEFENCE
            else -> this
        }
    }

    private fun BattlePokemonStateView.positiveBoosts(): Int = statStages.values.sumOf { it.coerceAtLeast(0) }

    private fun BattlePokemonStateView.stage(stat: CombatStat): Int = when (stat) {
        CombatStat.ATTACK -> stage("attack", "atk")
        CombatStat.DEFENCE -> stage("defence", "defense", "def")
        CombatStat.SPECIAL_ATTACK -> stage("special_attack", "specialattack", "spa")
        CombatStat.SPECIAL_DEFENCE -> stage(
            "special_defence", "special_defense", "specialdefence", "specialdefense", "spd",
        )
    }

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

    private val DYNAMIC_DAMAGE_FLAGS = setOf(
        "dynamic_base_power", "dynamic_move_type", "dynamic_damage_category", "dynamic_damage_value",
    )
    private val PUBLICLY_RESOLVED_DYNAMIC_MOVES = setOf(
        "storedpower", "powertrip", "facade", "hex", "infernalparade", "brine", "venoshock",
        "barbbarrage", "smellingsalts", "wakeupslap", "round", "fishiousrend", "boltbeak",
        "assurance", "payback", "avalanche", "revenge",
    )

    /** Fallback for synthetic/older candidates that predate declarative callback flags. */
    private val LEGACY_UNRESOLVED_DYNAMIC_MOVES = setOf(
        "acrobatics", "crushgrip", "echoedvoice", "electroball",
        "eruption", "expandingforce", "flail", "frustration", "furycutter", "gyroball",
        "heatcrash", "heavyslam", "iceball", "lastrespects", "lowkick", "magnitude",
        "present", "punishment", "ragefist", "return", "reversal", "risingvoltage", "rollout",
        "shellsidearm", "stompingtantrum", "terrainpulse", "trumpcard", "waterspout",
        "weatherball", "wringout",
    )
}
