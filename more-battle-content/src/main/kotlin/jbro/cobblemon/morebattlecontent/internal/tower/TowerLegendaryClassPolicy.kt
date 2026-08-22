package jbro.cobblemon.morebattlecontent.internal.tower

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
        val normalizedLabels = labels.mapTo(HashSet()) { it.lowercase().replace('-', '_') }
        val category = when {
            species in PARADOX || "paradox" in normalizedLabels -> TowerLegendaryClassCategory.PARADOX
            species in ULTRA_BEASTS || "ultra_beast" in normalizedLabels || "ultrabeast" in normalizedLabels ->
                TowerLegendaryClassCategory.ULTRA_BEAST
            species in MYTHICALS || "mythical" in normalizedLabels -> TowerLegendaryClassCategory.MYTHICAL
            species in LEGENDARIES || "legendary" in normalizedLabels -> TowerLegendaryClassCategory.LEGENDARY
            else -> return null
        }
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

    private val LEGENDARIES = speciesSet(
        "articuno zapdos moltres mewtwo raikou entei suicune lugia hooh regirock regice registeel latias latios " +
            "kyogre groudon rayquaza uxie mesprit azelf dialga palkia heatran regigigas giratina cresselia cobalion " +
            "terrakion virizion tornadus thundurus reshiram zekrom landorus kyurem xerneas yveltal zygarde " +
            "type_null typenull silvally tapu_koko tapukoko tapu_lele tapulele tapu_bulu tapubulu tapu_fini tapufini " +
            "cosmog cosmoem solgaleo lunala necrozma zacian zamazenta eternatus kubfu urshifu regieleki regidrago " +
            "glastrier spectrier calyrex enamorus wo_chien wochien chien_pao chienpao ting_lu tinglu chi_yu chiyu " +
            "koraidon miraidon okidogi munkidori fezandipiti ogerpon terapagos"
    )
    private val MYTHICALS = speciesSet(
        "mew celebi jirachi deoxys phione manaphy darkrai shaymin arceus victini keldeo meloetta genesect diancie " +
            "hoopa volcanion magearna marshadow zeraora meltan melmetal zarude pecharunt"
    )
    private val ULTRA_BEASTS = speciesSet(
        "nihilego buzzwole pheromosa xurkitree celesteela kartana guzzlord poipole naganadel stakataka blacephalon"
    )
    private val PARADOX = speciesSet(
        "great_tusk greattusk scream_tail screamtail brute_bonnet brutebonnet flutter_mane fluttermane " +
            "slither_wing slitherwing sandy_shocks sandyshocks roaring_moon roaringmoon iron_treads irontreads " +
            "iron_bundle ironbundle iron_hands ironhands iron_jugulis ironjugulis iron_moth ironmoth iron_thorns " +
            "ironthorns iron_valiant ironvaliant walking_wake walkingwake iron_leaves ironleaves gouging_fire " +
            "gougingfire raging_bolt ragingbolt iron_boulder ironboulder iron_crown ironcrown koraidon miraidon"
    )

    private fun speciesSet(values: String): Set<String> = values.split(' ').filter(String::isNotBlank).toSet()
}
