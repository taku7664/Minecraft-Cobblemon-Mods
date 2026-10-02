package jbro.cobblemon.policy.legend

/** Generated from docs/LEGENDARY_SPAWNS.md; keep the two in step. */
internal object LegendCatalog {
    private val entries = listOf(
        Legend("arceus", LegendTier.MYTHICAL, LegendRank.CHAMPION, listOf("dialga", "palkia", "giratina")),
        Legend("articuno", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("articuno", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, aspect = "galarian", id = "articuno-galar"),
        Legend("azelf", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("calyrex", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("glastrier", "spectrier")),
        Legend("celebi", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("chienpao", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("chiyu", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("cobalion", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("cosmoem", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("cosmog", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("cresselia", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("darkrai", LegendTier.MYTHICAL, LegendRank.MASTER_BALL, listOf("cresselia")),
        Legend("deoxys", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("dialga", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("uxie", "mesprit", "azelf")),
        Legend("diancie", LegendTier.MYTHICAL, LegendRank.MASTER_BALL, listOf("carbink")),
        Legend("enamorus", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, listOf("tornadus", "thundurus", "landorus")),
        Legend("entei", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("eternatus", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("zacian", "zamazenta")),
        Legend("fezandipiti", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("genesect", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("giratina", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("dialga", "palkia")),
        Legend("glastrier", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("gougingfire", LegendTier.PARADOX, LegendRank.CHAMPION, listOf("koraidon")),
        Legend("groudon", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("heatran", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("hooh", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("raikou", "entei", "suicune")),
        Legend("hoopa", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("ironboulder", LegendTier.PARADOX, LegendRank.CHAMPION, listOf("miraidon")),
        Legend("ironcrown", LegendTier.PARADOX, LegendRank.CHAMPION, listOf("miraidon")),
        Legend("ironleaves", LegendTier.PARADOX, LegendRank.CHAMPION, listOf("miraidon")),
        Legend("jirachi", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("keldeo", LegendTier.MYTHICAL, LegendRank.MASTER_BALL, listOf("cobalion", "terrakion", "virizion")),
        Legend("koraidon", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("kubfu", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("kyogre", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("kyurem", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("reshiram", "zekrom")),
        Legend("landorus", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, listOf("tornadus", "thundurus")),
        Legend("latias", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("latios", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("lugia", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("articuno", "zapdos", "moltres")),
        Legend("lunala", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("cosmog", "cosmoem")),
        Legend("magearna", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("manaphy", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("marshadow", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("melmetal", LegendTier.MYTHICAL, LegendRank.MASTER_BALL, listOf("meltan")),
        Legend("meloetta", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("meltan", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("mesprit", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("mew", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("mewtwo", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("mew")),
        Legend("miraidon", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("moltres", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("moltres", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, aspect = "galarian", id = "moltres-galar"),
        Legend("munkidori", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("necrozma", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("solgaleo", "lunala")),
        Legend("ogerpon", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, listOf("okidogi", "munkidori", "fezandipiti")),
        Legend("okidogi", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("palkia", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("uxie", "mesprit", "azelf")),
        Legend("pecharunt", LegendTier.MYTHICAL, LegendRank.MASTER_BALL, listOf("okidogi", "munkidori", "fezandipiti")),
        Legend("phione", LegendTier.MYTHICAL, LegendRank.MASTER_BALL, listOf("manaphy")),
        Legend("ragingbolt", LegendTier.PARADOX, LegendRank.CHAMPION, listOf("koraidon")),
        Legend("raikou", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("rayquaza", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("groudon", "kyogre")),
        Legend("regice", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("regidrago", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("regieleki", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("regigigas", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, listOf("regirock", "regice", "registeel"), entryAll = true),
        Legend("regirock", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("registeel", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("reshiram", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("shaymin", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("silvally", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, listOf("typenull")),
        Legend("solgaleo", LegendTier.RESTRICTED, LegendRank.CHAMPION, listOf("cosmog", "cosmoem")),
        Legend("spectrier", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("suicune", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("tapubulu", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("tapufini", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("tapukoko", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("tapulele", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("terapagos", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("terrakion", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("thundurus", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("tinglu", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("tornadus", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("typenull", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("urshifu", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, listOf("kubfu")),
        Legend("uxie", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("victini", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("virizion", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("volcanion", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("walkingwake", LegendTier.PARADOX, LegendRank.CHAMPION, listOf("koraidon")),
        Legend("wochien", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("xerneas", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("yveltal", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("zacian", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("zamazenta", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("zapdos", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL),
        Legend("zapdos", LegendTier.LEGENDARY, LegendRank.ULTRA_BALL, aspect = "galarian", id = "zapdos-galar"),
        Legend("zarude", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("zekrom", LegendTier.RESTRICTED, LegendRank.CHAMPION),
        Legend("zeraora", LegendTier.MYTHICAL, LegendRank.MASTER_BALL),
        Legend("zygarde", LegendTier.RESTRICTED, LegendRank.CHAMPION),
    )

    val byId: Map<String, Legend> = entries.associateBy { it.id }
    private val bySpecies: Map<String, List<Legend>> = entries.groupBy { it.species }

    /** A Legend by [id], such as `zapdos` or `zapdos-galar`, with or without the `cobblemon:` namespace. */
    operator fun get(id: String): Legend? = byId[id.substringAfter(':').lowercase()]

    /** Whether some form of [species] is a Legend of its own, so its aspects decide which Legend a Pokemon is. */
    fun hasForms(species: String): Boolean = bySpecies[key(species)].orEmpty().any { it.aspect != null }

    /**
     * The Legend a Pokemon of [species] with [aspects] is: its regional form's own when one matches, else the
     * species'. A form without its own Legend, say a Galarian form of a species listed only once, counts as the species.
     */
    fun of(species: String, aspects: Collection<String>): Legend? {
        val forms = bySpecies[key(species)] ?: return null
        return forms.firstOrNull { it.aspect != null && it.aspect in aspects } ?: forms.firstOrNull { it.aspect == null }
    }

    private fun key(species: String) = species.substringAfter(':').lowercase()
}
