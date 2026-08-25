package jbro.cobblemon.morebattlecontent.internal.battle

internal enum class LegendaryClassCategory {
    LEGENDARY,
    MYTHICAL,
    ULTRA_BEAST,
    PARADOX,
}

/** Shared species classification used by facilities without coupling their progression rules. */
internal object LegendaryClassPolicy {
    fun categoryFor(speciesId: String, labels: Collection<String> = emptySet()): LegendaryClassCategory? {
        val species = speciesId.substringAfter(':').lowercase()
        val normalizedLabels = labels.mapTo(HashSet()) { it.lowercase().replace('-', '_') }
        return when {
            species in PARADOX || "paradox" in normalizedLabels -> LegendaryClassCategory.PARADOX
            species in ULTRA_BEASTS || "ultra_beast" in normalizedLabels || "ultrabeast" in normalizedLabels ->
                LegendaryClassCategory.ULTRA_BEAST
            species in MYTHICALS || "mythical" in normalizedLabels -> LegendaryClassCategory.MYTHICAL
            species in LEGENDARIES || "legendary" in normalizedLabels -> LegendaryClassCategory.LEGENDARY
            else -> null
        }
    }

    fun isLegendaryClass(speciesId: String, labels: Collection<String> = emptySet()): Boolean =
        categoryFor(speciesId, labels) != null

    private val LEGENDARIES = speciesSet(
        "articuno zapdos moltres mewtwo raikou entei suicune lugia hooh regirock regice registeel latias latios " +
            "kyogre groudon rayquaza uxie mesprit azelf dialga palkia heatran regigigas giratina cresselia cobalion " +
            "terrakion virizion tornadus thundurus reshiram zekrom landorus kyurem xerneas yveltal zygarde " +
            "type_null typenull silvally tapu_koko tapukoko tapu_lele tapulele tapu_bulu tapubulu tapu_fini tapufini " +
            "cosmog cosmoem solgaleo lunala necrozma zacian zamazenta eternatus kubfu urshifu regieleki regidrago " +
            "glastrier spectrier calyrex enamorus wo_chien wochien chien_pao chienpao ting_lu tinglu chi_yu chiyu " +
            "koraidon miraidon okidogi munkidori fezandipiti ogerpon terapagos",
    )
    private val MYTHICALS = speciesSet(
        "mew celebi jirachi deoxys phione manaphy darkrai shaymin arceus victini keldeo meloetta genesect diancie " +
            "hoopa volcanion magearna marshadow zeraora meltan melmetal zarude pecharunt",
    )
    private val ULTRA_BEASTS = speciesSet(
        "nihilego buzzwole pheromosa xurkitree celesteela kartana guzzlord poipole naganadel stakataka blacephalon",
    )
    private val PARADOX = speciesSet(
        "great_tusk greattusk scream_tail screamtail brute_bonnet brutebonnet flutter_mane fluttermane " +
            "slither_wing slitherwing sandy_shocks sandyshocks roaring_moon roaringmoon iron_treads irontreads " +
            "iron_bundle ironbundle iron_hands ironhands iron_jugulis ironjugulis iron_moth ironmoth iron_thorns " +
            "ironthorns iron_valiant ironvaliant walking_wake walkingwake iron_leaves ironleaves gouging_fire " +
            "gougingfire raging_bolt ragingbolt iron_boulder ironboulder iron_crown ironcrown koraidon miraidon",
    )

    private fun speciesSet(values: String): Set<String> = values.split(' ').filter(String::isNotBlank).toSet()
}
