package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.internal.battle.LegendaryClassPolicy

internal enum class TowerLegendaryClassCategory {
    LEGENDARY,
    MYTHICAL,
    ULTRA_BEAST,
    PARADOX,
}

internal data class TowerLegendaryClassEntry(
    val category: TowerLegendaryClassCategory,
    val singlesPowerGrade: Int,
    val fameWeight: Int,
) {
    init {
        require(singlesPowerGrade in 1..4) { "Singles power grade must be between one and four" }
        require(fameWeight in 1..4) { "Fame weight must be between one and four" }
    }
}

/**
 * Shared Battle Tower definition of "legendary class". Species lists are a compatibility fallback
 * for Cobblemon data packs whose labels predate one of the four supported category labels.
 * Power grades are singles-oriented: 1 accessible, 2 strong, 3 elite, 4 oppressive.
 */
internal object TowerLegendaryClassPolicy {
    fun entryFor(speciesId: String, labels: Collection<String> = emptySet()): TowerLegendaryClassEntry? {
        val species = speciesId.substringAfter(':').lowercase()
        val category = LegendaryClassPolicy.categoryFor(speciesId, labels)
            ?.let { TowerLegendaryClassCategory.valueOf(it.name) }
            ?: return null
        return TowerLegendaryClassEntry(
            category = category,
            singlesPowerGrade = POWER_GRADES[species] ?: 2,
            fameWeight = FAME_WEIGHTS[species] ?: 1,
        )
    }

    fun isLegendaryClass(speciesId: String, labels: Collection<String> = emptySet()): Boolean =
        entryFor(speciesId, labels) != null

    fun selectionWeight(stage: TowerStreakStage, entry: TowerLegendaryClassEntry): Long =
        Math.multiplyExact(
            POWER_WEIGHTS.getValue(stage)[entry.singlesPowerGrade - 1].toLong(),
            entry.fameWeight.toLong(),
        )

    private val POWER_WEIGHTS = mapOf(
        TowerStreakStage.INTRODUCTORY to listOf(55, 30, 12, 3),
        TowerStreakStage.PRACTICAL to listOf(30, 40, 23, 7),
        TowerStreakStage.ADVANCED to listOf(12, 28, 40, 20),
        TowerStreakStage.PRO to listOf(4, 12, 34, 50),
    )

    private val POWER_GRADES = buildMap {
        setOf(
            "arceus", "calyrex", "eternatus", "koraidon", "miraidon", "zacian", "xerneas", "yveltal",
            "kyogre", "groudon", "rayquaza", "necrozma", "zygarde", "marshadow", "mewtwo", "hooh",
            "flutter_mane", "fluttermane", "chien_pao", "chienpao", "chi_yu", "chiyu", "darkrai",
            "deoxys", "dialga", "giratina", "palkia", "kyurem", "lugia", "magearna", "naganadel",
        ).forEach { put(it, 4) }
        setOf(
            "enamorus", "landorus", "thundurus", "tornadus", "latios", "latias", "cresselia", "diancie",
            "glastrier", "spectrier", "zamazenta", "terapagos", "ogerpon", "raging_bolt", "ragingbolt",
            "great_tusk", "greattusk", "iron_valiant", "ironvaliant", "roaring_moon", "roaringmoon",
            "iron_bundle", "ironbundle", "walking_wake", "walkingwake", "kartana", "pheromosa", "celesteela",
        ).forEach { put(it, 3) }
        setOf(
            "phione", "cosmog", "cosmoem", "regigigas", "articuno", "moltres", "fezandipiti", "okidogi",
            "munkidori", "brute_bonnet", "brutebonnet", "scream_tail", "screamtail", "iron_jugulis",
            "ironjugulis", "guzzlord", "stakataka",
        ).forEach { put(it, 1) }
    }

    private val FAME_WEIGHTS = buildMap {
        setOf(
            "mewtwo", "mew", "lugia", "hooh", "kyogre", "groudon", "rayquaza", "dialga", "palkia",
            "giratina", "arceus", "reshiram", "zekrom", "xerneas", "yveltal", "solgaleo", "lunala",
            "zacian", "zamazenta", "koraidon", "miraidon",
        ).forEach { put(it, 4) }
        setOf(
            "articuno", "zapdos", "moltres", "raikou", "entei", "suicune", "latias", "latios", "darkrai",
            "deoxys", "kyurem", "necrozma", "eternatus", "calyrex", "chien_pao", "chienpao", "chi_yu",
            "chiyu", "flutter_mane", "fluttermane", "ogerpon", "terapagos",
        ).forEach { put(it, 3) }
    }

}
