package jbro.cobblemon.morebattlecontent.internal.tower.opponent

internal enum class TowerTrainerStyle(val serializedId: String) {
    BALANCED("balanced"),
    PHYSICAL_PRESSURE("physical_pressure"),
    SPECIAL_PRESSURE("special_pressure"),
    SETUP_SWEEP("setup_sweep"),
    ENDURANCE("endurance"),
    FIELD_CONTROL("field_control"),
    SPEED_CONTROL("speed_control"),
    WEATHER_CONTROL("weather_control"),
    ;

    fun matches(set: TowerPokemonSet): Boolean = when (this) {
        BALANCED -> true
        PHYSICAL_PRESSURE -> set.natureId.path() in PHYSICAL_NATURES || set.evs.attack > set.evs.specialAttack
        SPECIAL_PRESSURE -> set.natureId.path() in SPECIAL_NATURES || set.evs.specialAttack > set.evs.attack
        SETUP_SWEEP -> set.hasAnyMove(SETUP_MOVES)
        ENDURANCE -> set.hasAnyMove(ENDURANCE_MOVES)
        FIELD_CONTROL -> set.hasAnyMove(FIELD_CONTROL_MOVES)
        SPEED_CONTROL -> set.hasAnyMove(SPEED_CONTROL_MOVES)
        WEATHER_CONTROL -> set.hasAnyMove(WEATHER_MOVES) || set.abilityId?.path() in WEATHER_ABILITIES
    }

    companion object {
        fun fromSerializedId(id: String): TowerTrainerStyle? = entries.singleOrNull { it.serializedId == id }

        private val PHYSICAL_NATURES = setOf("adamant", "jolly", "brave", "impish", "careful")
        private val SPECIAL_NATURES = setOf("modest", "timid", "quiet", "bold", "calm")
        private val SETUP_MOVES = setOf(
            "agility", "bulkup", "calmmind", "coil", "curse", "dragondance", "irondefense",
            "nastyplot", "quiverdance", "rockpolish", "shellsmash", "swordsdance",
        )
        private val ENDURANCE_MOVES = setOf(
            "leechseed", "morningsun", "protect", "recover", "rest", "roost", "slackoff",
            "softboiled", "synthesis", "toxic", "wish",
        )
        private val FIELD_CONTROL_MOVES = setOf(
            "auroraveil", "defog", "lightscreen", "rapidspin", "reflect", "spikes",
            "stealthrock", "stickyweb", "toxicspikes",
        )
        private val SPEED_CONTROL_MOVES = setOf(
            "bulldoze", "electroweb", "icywind", "nuzzle", "rocktomb", "tailwind",
            "thunderwave", "trickroom",
        )
        private val WEATHER_MOVES = setOf("hail", "raindance", "sandstorm", "snowscape", "sunnyday", "weatherball")
        private val WEATHER_ABILITIES = setOf("drizzle", "drought", "sandstream", "snowwarning")
    }
}

private fun TowerPokemonSet.hasAnyMove(candidates: Set<String>): Boolean = moves.any { it.path() in candidates }
private fun String.path(): String = substringAfter(':').lowercase()
