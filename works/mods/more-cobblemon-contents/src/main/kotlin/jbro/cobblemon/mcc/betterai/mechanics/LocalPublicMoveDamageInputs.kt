package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

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
        if (id == "pollenpuff" && target.side == actor.side) return null
        val wholePower = details.power.toInt().takeIf { it > 0 && it.toDouble() == details.power }
        val dynamicPower = speedRatioPower(id, actor, target, state)
            ?: hpDependentPowers(id, actor, wholePower)
            ?: targetHpDependentPowers(id, target)
            ?: ppDependentPower(id, actor, details.currentPp)
        val fixedPower = when (id) {
            "acrobatics" -> wholePower?.let { if (actor.canonicalKnownHeldItemId == null) it * 2 else it }
            // 1.5x only for a held item that is public and can be knocked off; an unknown item keeps the
            // printed power as the lower bound.
            "knockoff" -> wholePower?.let { if (knockOffBoosts(target)) it * 3 / 2 else it }
            "weatherball" -> wholePower?.let {
                if (weatherBallWeather(actor, state) != null) it * 2 else it
            }
            "terrainpulse" -> wholePower?.let {
                if (LocalPublicFieldMechanics.terrainId(state) != null &&
                    LocalPublicTurnOrder.grounded(state, actor)
                ) it * 2 else it
            }
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
            "mistyexplosion" -> wholePower?.let {
                if (LocalPublicFieldMechanics.terrainId(state) == "mistyterrain" &&
                    LocalPublicTurnOrder.grounded(state, actor)
                ) it * 3 / 2 else it
            }
            "psyblade" -> wholePower?.let {
                if (LocalPublicFieldMechanics.terrainId(state) == "electricterrain") it * 3 / 2 else it
            }
            "collisioncourse", "electrodrift" -> superEffectivePower(
                printedPower = wholePower,
                moveType = details.typeId,
                targetTypes = target.knownTypeIds,
            )
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
            // Weight is species data, so these are public. Unknown weight leaves the move unresolved.
            "lowkick", "grassknot" -> weightKg(target, state)?.let(::targetWeightPower) ?: return null
            "heavyslam", "heatcrash" -> {
                val user = weightKg(actor, state) ?: return null
                val defender = weightKg(target, state)?.takeIf { it > 0.0 } ?: return null
                ratioWeightPower(user / defender)
            }
            // The escalating hits average to the middle one; per-hit accuracy is applied by the multi-hit rules.
            // Beat Up: one hit per healthy party member, each 5 + its base Attack / 10, read here as their sum.
            "beatup" -> state.pokemon.filter { it.side == actor.side && !it.fainted && it.hpFraction > 0.0 && it.statusId == null &&
                candidate.tags.firstOrNull { tag -> tag.startsWith("better_ai:beatup_member=") }?.substringAfter('=')
                    ?.let { selected -> selected == it.battlePokemonId.toString() } != false }
                .sumOf { (LocalPublicSpeciesData.species(it)?.baseStats?.get("atk") ?: 0) / 10 + 5 }.takeIf { it > 0 }
            "tripleaxel" -> candidate.tags.firstOrNull { it.startsWith("better_ai:hit_index=") }
                ?.substringAfter('=')?.toIntOrNull()?.takeIf { it in 1..3 }?.times(20) ?: 40
            "triplekick" -> candidate.tags.firstOrNull { it.startsWith("better_ai:hit_index=") }
                ?.substringAfter('=')?.toIntOrNull()?.takeIf { it in 1..3 }?.times(10) ?: 20
            // Showdown halves Solar Beam and Solar Blade in rain, sand and snow.
            "solarbeam", "solarblade" -> wholePower?.let {
                if (LocalPublicFieldMechanics.effectiveWeatherId(state) in SOLAR_WEAK_WEATHER) it / 2 else it
            }
            "gravapple" -> wholePower?.let { if (LocalPublicFieldMechanics.gravityActive(state)) it * 3 / 2 else it }
            "terablast" -> wholePower?.let { if (canonical(actor.knownTeraTypeId) == "stellar") 100 else it }
            "lastrespects" -> 50 + 50 * state.pokemon.count { it.side == actor.side && it.fainted }
            "return" -> 102
            "frustration" -> 1
            // The printed power is the floor of a condition the public board cannot settle (a failed move last
            // turn, an ally fusion move, a hit counter, the Fickle Beam roll, a stat drop this turn, a switch
            // out), so these take it rather than zero.
            in PRINTED_POWER_FLOOR_MOVES -> wholePower
            else -> wholePower
        }
        val powers = dynamicPower ?: when (id) {
            in SPEED_RATIO_MOVES, in HP_DEPENDENT_MOVES, in TARGET_HP_DEPENDENT_MOVES,
            in PP_DEPENDENT_MOVES -> return null
            else -> fixedPower?.let(::setOf) ?: return null
        }
        val offensivePokemon = if (id == "foulplay") target else actor
        val physicalIfStronger = id == "photongeyser" || id == "shellsidearm" || id == "terastarstorm" ||
            id == "terablast" && actor.knownTeraTypeId != null
        val offensiveStat = (
            overrideStat(details, "override_offensive_stat") ?: when {
                id == "bodypress" -> CombatStat.DEFENCE
                // Photon Geyser, Tera Blast once terastallized and Shell Side Arm use the higher attacking stat.
                physicalIfStronger -> if (attackExceedsSpecialAttack(actor)) CombatStat.ATTACK else CombatStat.SPECIAL_ATTACK
                details.damageCategory == BattleMoveDamageCategory.SPECIAL -> CombatStat.SPECIAL_ATTACK
                else -> CombatStat.ATTACK
            }
            ).swapDefencesIfWonderRoom(state)
        val defensiveStat = (
            overrideStat(details, "override_defensive_stat") ?: if (physicalIfStronger && id != "shellsidearm") {
                if (attackExceedsSpecialAttack(actor)) CombatStat.DEFENCE else CombatStat.SPECIAL_DEFENCE
            } else when (details.damageCategory) {
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

    /** Resolves a move type callback only when every input is already public. */
    fun resolvedTypeId(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView,
        state: BattleStateView,
    ): String? {
        val details = candidate.moveDetails ?: return null
        // Pixilate and its kind turn Normal moves into their type; Normalize turns every move Normal.
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) {
            val ability = LocalPublicAbilityState.effectiveKnownAbility(state, actor)
            if (ability == "normalize") return "normal"
            ATE_ABILITY_TYPES[ability]?.let { if (canonical(details.typeId) == "normal") return it }
        }
        return when (canonical(candidate.moveId)) {
            "weatherball" -> when (weatherBallWeather(actor, state)) {
                in FIRE_WEATHER -> "fire"
                in WATER_WEATHER -> "water"
                in ROCK_WEATHER -> "rock"
                in ICE_WEATHER -> "ice"
                else -> details.typeId
            }
            "terrainpulse" -> if (LocalPublicTurnOrder.grounded(state, actor)) {
                TERRAIN_TYPES[LocalPublicFieldMechanics.terrainId(state)] ?: details.typeId
            } else {
                details.typeId
            }
            // Ogerpon's mask sets the type (`onModifyType`); the form is public.
            "ivycudgel" -> ivyCudgelType(actor) ?: details.typeId
            "terablast" -> actor.knownTeraTypeId?.takeIf { canonical(it) != "stellar" } ?: details.typeId
            // The held Plate sets Judgment; the Arceus form says the same.
            "judgment" -> PLATE_TYPES[canonical(actor.knownHeldItemId)]
                ?: canonical(actor.formId).takeIf { it in TYPE_IDS }
                ?: details.typeId
            "multiattack" -> canonical(actor.knownHeldItemId).takeIf { it.endsWith("memory") }?.removeSuffix("memory")
                ?.takeIf { it in TYPE_IDS } ?: details.typeId
            "revelationdance" -> (actor.knownTeraTypeId?.takeIf { canonical(it) != "stellar" }
                ?: actor.knownTypeIds.firstOrNull()) ?: details.typeId
            "aurawheel" -> if (canonical(actor.formId).contains("hangry")) "dark" else details.typeId
            "ragingbull" -> when {
                canonical(actor.formId).contains("blaze") -> "fire"
                canonical(actor.formId).contains("aqua") -> "water"
                canonical(actor.formId).contains("combat") -> "fighting"
                else -> details.typeId
            }
            else -> details.typeId
        }
    }

    private fun attackExceedsSpecialAttack(actor: BattlePokemonStateView): Boolean {
        val stats = actor.combatStats ?: return false
        fun staged(value: Int, stage: Int) = value * if (stage >= 0) (2 + stage) / 2.0 else 2.0 / (2 - stage)
        val attack = staged((stats.attack.minimum + stats.attack.maximum) / 2, actor.stage(CombatStat.ATTACK))
        val special = staged((stats.specialAttack.minimum + stats.specialAttack.maximum) / 2, actor.stage(CombatStat.SPECIAL_ATTACK))
        return attack > special
    }

    /** Public weight in kg: species data, halved by Light Metal or a Float Stone and doubled by Heavy Metal. */
    private fun weightKg(pokemon: BattlePokemonStateView, state: BattleStateView): Double? {
        val kg = LocalPublicSpeciesData.weightKg(pokemon) ?: return null
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon)
        val item = LocalPublicItemState.activeItemId(state, pokemon)
        var weight = kg
        if (ability == "heavymetal") weight *= 2.0
        if (ability == "lightmetal") weight /= 2.0
        if (item == "floatstone") weight /= 2.0
        return weight.coerceAtLeast(0.1)
    }

    private fun targetWeightPower(kg: Double): Int = when {
        kg >= 200.0 -> 120
        kg >= 100.0 -> 100
        kg >= 50.0 -> 80
        kg >= 25.0 -> 60
        kg >= 10.0 -> 40
        else -> 20
    }

    private fun ratioWeightPower(ratio: Double): Int = when {
        ratio >= 5.0 -> 120
        ratio >= 4.0 -> 100
        ratio >= 3.0 -> 80
        ratio >= 2.0 -> 60
        else -> 40
    }

    private fun ivyCudgelType(actor: BattlePokemonStateView): String? {
        val form = canonical(actor.speciesId + (actor.formId ?: ""))
        return IVY_CUDGEL_FORMS.entries.firstOrNull { (key, _) -> key in form }?.value
    }

    /** Showdown boosts Knock Off when the target holds an item it can lose (not a mask, Mega Stone or the like). */
    private fun knockOffBoosts(target: BattlePokemonStateView): Boolean {
        val item = canonical(target.knownHeldItemId).takeIf { it.isNotEmpty() } ?: return false
        return item !in UNREMOVABLE_ITEMS && !item.endsWith("mask") && !MEGA_STONE.matches(item)
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

    private fun superEffectivePower(
        printedPower: Int?,
        moveType: String,
        targetTypes: Set<String>,
    ): Int? {
        val power = printedPower ?: return null
        if (targetTypes.isEmpty()) return null
        val effectiveness = StandardTypeEffectiveness.multiplier(moveType, targetTypes)
        return if (effectiveness > 1.0) showdownModify(power, 5461, 4096) else power
    }

    private fun showdownModify(value: Int, numerator: Int, denominator: Int): Int {
        val modifier = numerator.toLong() * 4096L / denominator
        return ((value.toLong() * modifier + 2047L) / 4096L).toInt()
    }

    private fun ppDependentPower(
        id: String,
        actor: BattlePokemonStateView,
        currentPp: Int,
    ): Set<Int>? {
        if (id !in PP_DEPENDENT_MOVES || actor.side != BattleSide.ALLY || currentPp <= 0) return null
        val ppAfterUse = currentPp - 1
        val power = when (ppAfterUse) {
            0 -> 200
            1 -> 80
            2 -> 60
            3 -> 50
            else -> 40
        }
        return setOf(power)
    }

    private fun weatherBallWeather(
        actor: BattlePokemonStateView,
        state: BattleStateView,
    ): String? {
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(state) ?: return null
        val umbrellaActive = LocalPublicItemState.activeItemId(state, actor) == "utilityumbrella"
        return weather.takeUnless { umbrellaActive && it in UMBRELLA_SUPPRESSED_WEATHER }
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

    private fun canonical(value: String?): String = value?.let(PublicIds::canonical)
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
        "powertrip", "punishment", "trumpcard", "weatherball", "terrainpulse", "mistyexplosion",
        "psyblade", "collisioncourse", "electrodrift", "facade", "hex",
        "infernalparade", "brine", "venoshock",
        "barbbarrage", "smellingsalts", "wakeupslap", "round", "fishiousrend", "boltbeak",
        "assurance", "payback", "avalanche", "revenge", "electroball", "gyroball",
        "knockoff", "ivycudgel",
        "beatup", "lowkick", "grassknot", "heavyslam", "heatcrash", "tripleaxel", "triplekick", "solarbeam", "solarblade",
        "gravapple", "terablast", "lastrespects", "return", "frustration", "judgment", "multiattack",
        "revelationdance", "aurawheel", "ragingbull", "photongeyser", "shellsidearm", "terastarstorm", "pollenpuff",
        "stompingtantrum", "temperflare", "fusionflare", "fusionbolt", "ragefist", "echoedvoice", "furycutter",
        "rollout", "iceball", "ficklebeam", "lashout", "pursuit", "retaliate",
    )
    private val ATE_ABILITY_TYPES = mapOf(
        "pixilate" to "fairy", "refrigerate" to "ice", "aerilate" to "flying", "galvanize" to "electric",
    )
    private val PRINTED_POWER_FLOOR_MOVES = setOf(
        "stompingtantrum", "temperflare", "fusionflare", "fusionbolt", "ragefist", "echoedvoice", "furycutter",
        "rollout", "iceball", "ficklebeam", "lashout", "pursuit", "retaliate",
    )
    private val SOLAR_WEAK_WEATHER = setOf("rain", "raindance", "primordialsea", "sand", "sandstorm", "hail", "snow", "snowscape")
    private val TYPE_IDS = setOf(
        "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground", "flying", "psychic",
        "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )
    private val PLATE_TYPES = mapOf(
        "flameplate" to "fire", "splashplate" to "water", "zapplate" to "electric", "meadowplate" to "grass",
        "icicleplate" to "ice", "fistplate" to "fighting", "toxicplate" to "poison", "earthplate" to "ground",
        "skyplate" to "flying", "mindplate" to "psychic", "insectplate" to "bug", "stoneplate" to "rock",
        "spookyplate" to "ghost", "dracoplate" to "dragon", "dreadplate" to "dark", "ironplate" to "steel",
        "pixieplate" to "fairy",
    )
    private val IVY_CUDGEL_FORMS = mapOf("wellspring" to "water", "hearthflame" to "fire", "cornerstone" to "rock")
    /** Items whose `onTakeItem` refuses removal for their usual holder, so Knock Off gets no boost. */
    private val UNREMOVABLE_ITEMS = setOf(
        "blueorb", "redorb", "griseouscore", "adamantcrystal", "lustrousglobe", "rustedsword", "rustedshield",
    )
    private val MEGA_STONE = Regex("(?!eviolite).+ite[xyz]?")

    private val SPEED_RATIO_MOVES = setOf("electroball", "gyroball")
    private val HP_DEPENDENT_MOVES = setOf(
        "eruption", "waterspout", "dragonenergy", "flail", "reversal",
    )
    private val TARGET_HP_DEPENDENT_MOVES = setOf("crushgrip", "wringout")
    private val PP_DEPENDENT_MOVES = setOf("trumpcard")
    private val ELECTRO_BALL_POWERS = listOf(40, 60, 80, 120, 150)
    private val FIRE_WEATHER = setOf("sun", "sunnyday", "harshsunlight", "desolateland")
    private val WATER_WEATHER = setOf("rain", "raindance", "heavyrain", "primordialsea")
    private val ROCK_WEATHER = setOf("sand", "sandstorm")
    private val ICE_WEATHER = setOf("hail", "snow", "snowscape")
    private val UMBRELLA_SUPPRESSED_WEATHER = FIRE_WEATHER + WATER_WEATHER
    private val TERRAIN_TYPES = mapOf(
        "electricterrain" to "electric",
        "grassyterrain" to "grass",
        "mistyterrain" to "fairy",
        "psychicterrain" to "psychic",
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
