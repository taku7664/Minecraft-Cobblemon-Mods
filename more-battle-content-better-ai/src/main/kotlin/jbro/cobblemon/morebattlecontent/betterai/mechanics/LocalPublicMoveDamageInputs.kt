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
        val powers: Set<Int>,
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
        val wholePower = details.power.toInt().takeIf { it > 0 && it.toDouble() == details.power }
        val dynamicPower = speedRatioPower(id, actor, target, state)
            ?: hpDependentPowers(id, actor, wholePower)
            ?: targetHpDependentPowers(id, target)
        val fixedPower = when (id) {
            "acrobatics" -> wholePower?.let { if (actor.knownHeldItemId == null) it * 2 else it }
            "expandingforce" -> wholePower?.let {
                if (LocalPublicFieldMechanics.terrainId(state) == "psychicterrain" &&
                    LocalPublicTurnOrder.grounded(state, actor)
                ) it * 3 / 2 else it
            }
            "risingvoltage" -> wholePower?.let {
                if (LocalPublicFieldMechanics.terrainId(state) == "electricterrain" &&
                    LocalPublicTurnOrder.grounded(state, target)
                ) it * 2 else it
            }
            "storedpower", "powertrip" -> wholePower?.plus(20 * actor.positiveBoosts())
            "punishment" -> (60 + 20 * target.positiveBoosts()).coerceAtMost(200)
            "facade" -> wholePower?.let { if (actor.statusId != null) it * 2 else it }
            "hex", "infernalparade" -> wholePower?.let { if (target.statusId != null) it * 2 else it }
            "brine" -> wholePower?.let { if (target.hpFraction <= 0.5) it * 2 else it }
            "venoshock", "barbbarrage" -> wholePower?.let {
                if (canonical(target.statusId) in POISON_STATUSES) it * 2 else it
            }
            "smellingsalts" -> wholePower?.let {
                if (canonical(target.statusId) in PARALYSIS_STATUSES) it * 2 else it
            }
            "wakeupslap" -> wholePower?.let {
                if (canonical(target.statusId) in SLEEP_STATUSES) it * 2 else it
            }
            else -> wholePower
        }
        val powers = dynamicPower ?: when (id) {
            in SPEED_RATIO_MOVES, in HP_DEPENDENT_MOVES, in TARGET_HP_DEPENDENT_MOVES -> return null
            else -> fixedPower?.let(::setOf) ?: return null
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
            powers = powers,
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

    /**
     * Public callback powers derived from current Speed.
     *
     * [LocalPublicTurnOrder.effectiveSpeed] mirrors Showdown's `getStat('spe')`: stages and public
     * Speed modifiers are included, while Trick Room is deliberately applied only by the action-order
     * comparison. That distinction is load-bearing for Electro Ball and Gyro Ball.
     */
    private fun speedRatioPower(
        id: String,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        state: BattleStateView,
    ): Set<Int>? {
        if (id !in SPEED_RATIO_MOVES) return null
        val actorSpeed = LocalPublicTurnOrder.effectiveSpeed(state, actor) ?: return null
        val targetSpeed = LocalPublicTurnOrder.effectiveSpeed(state, target) ?: return null
        return when (id) {
            "electroball" -> electroBallPowers(
                minimum = electroBallPower(actorSpeed.first, targetSpeed.second),
                maximum = electroBallPower(actorSpeed.second, targetSpeed.first),
            )
            "gyroball" -> boundarySensitivePowers(
                minimum = gyroBallPower(actorSpeed.second, targetSpeed.first),
                maximum = gyroBallPower(actorSpeed.first, targetSpeed.second),
            )
            else -> null
        }
    }

    private fun electroBallPowers(minimum: Int, maximum: Int): Set<Int> =
        ELECTRO_BALL_POWERS.filterTo(linkedSetOf()) { it in minimum..maximum }

    /** Endpoints plus Technician's only discontinuity are sufficient for damage-range extrema. */
    private fun boundarySensitivePowers(minimum: Int, maximum: Int): Set<Int> = buildSet {
        add(minimum)
        add(maximum)
        if (60 in minimum..maximum) add(60)
        if (61 in minimum..maximum) add(61)
    }

    private fun electroBallPower(actorSpeed: Int, targetSpeed: Int): Int = when {
        actorSpeed / targetSpeed >= 4 -> 150
        actorSpeed / targetSpeed == 3 -> 120
        actorSpeed / targetSpeed == 2 -> 80
        actorSpeed / targetSpeed == 1 -> 60
        else -> 40
    }

    private fun gyroBallPower(actorSpeed: Int, targetSpeed: Int): Int =
        ((25L * targetSpeed) / actorSpeed + 1L).coerceAtMost(150L).toInt()

    private fun hpDependentPowers(
        id: String,
        actor: BattlePokemonStateView,
        printedPower: Int?,
    ): Set<Int>? {
        if (id !in HP_DEPENDENT_MOVES) return null
        val hypotheses = LocalHpArithmetic.exactHpHypotheses(actor)
        if (hypotheses.isEmpty()) return null
        return hypotheses.mapTo(linkedSetOf()) { hp ->
            when (id) {
                "eruption", "waterspout", "dragonenergy" -> {
                    val base = printedPower ?: return null
                    ((base.toLong() * hp.current) / hp.maximum).coerceAtLeast(1L).toInt()
                }
                "flail", "reversal" -> flailPower(hp.current, hp.maximum)
                else -> error("Unhandled HP-dependent move: $id")
            }
        }
    }

    private fun flailPower(currentHp: Int, maximumHp: Int): Int {
        val ratio = ((currentHp.toLong() * 48L) / maximumHp).coerceAtLeast(1L)
        return when {
            ratio < 2L -> 200
            ratio < 5L -> 150
            ratio < 10L -> 100
            ratio < 17L -> 80
            ratio < 33L -> 40
            else -> 20
        }
    }

    private fun targetHpDependentPowers(
        id: String,
        target: BattlePokemonStateView,
    ): Set<Int>? {
        if (id !in TARGET_HP_DEPENDENT_MOVES) return null
        val hypotheses = LocalHpArithmetic.exactHpHypotheses(target)
        if (hypotheses.isEmpty()) return null
        return hypotheses.mapTo(linkedSetOf()) { hp -> crushGripPower(hp.current, hp.maximum) }
    }

    private fun crushGripPower(currentHp: Int, maximumHp: Int): Int {
        val hpRatio = currentHp.toLong() * 4096L / maximumHp
        val scaledPower = (120L * (100L * hpRatio) + 2047L) / 4096L
        return (scaledPower / 100L).coerceAtLeast(1L).toInt()
    }

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
        "acrobatics", "expandingforce", "risingvoltage", "eruption", "waterspout",
        "dragonenergy", "flail", "reversal", "crushgrip", "wringout", "storedpower",
        "powertrip", "punishment", "facade", "hex",
        "infernalparade", "brine", "venoshock",
        "barbbarrage", "smellingsalts", "wakeupslap", "round", "fishiousrend", "boltbeak",
        "assurance", "payback", "avalanche", "revenge", "electroball", "gyroball",
    )

    private val SPEED_RATIO_MOVES = setOf("electroball", "gyroball")
    private val HP_DEPENDENT_MOVES = setOf(
        "eruption", "waterspout", "dragonenergy", "flail", "reversal",
    )
    private val TARGET_HP_DEPENDENT_MOVES = setOf("crushgrip", "wringout")
    private val ELECTRO_BALL_POWERS = listOf(40, 60, 80, 120, 150)

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
