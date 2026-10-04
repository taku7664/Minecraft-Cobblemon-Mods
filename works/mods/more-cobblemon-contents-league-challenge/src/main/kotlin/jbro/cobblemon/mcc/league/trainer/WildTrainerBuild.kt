package jbro.cobblemon.mcc.league.trainer

import kotlin.math.ceil
import kotlin.random.Random

/** Where a level cap sits in a playthrough; each phase raises how well wild trainers raise their Pokemon. */
enum class WildTrainerPhase {
    EARLY,
    MID,
    LATE,
    END;

    companion object {
        fun of(cap: Int): WildTrainerPhase = when {
            cap < 25 -> EARLY
            cap < 45 -> MID
            cap < 65 -> LATE
            else -> END
        }
    }
}

/**
 * How well a trainer's Pokemon are raised. [smartMoves] is the chance each move slot is chosen for the Pokemon
 * rather than at random from what it knows; [tmMoves] the chance it also knows TM and tutor moves up to
 * [tmPowerCap]; [fittedNature] the chance of a nature that suits its stats. [evTotal] effort values go into the
 * stats it fights with. [itemChance] is the chance of holding anything; [skill] is Cobblemon's battle AI skill.
 */
data class WildTrainerQuality(
    val ivs: IntRange,
    val evTotal: Int,
    val fittedNature: Double,
    val smartMoves: Double,
    val tmMoves: Double,
    val tmPowerCap: Int,
    val maxStatusMoves: Int,
    val itemChance: Double,
    val skill: Int,
) {
    companion object {
        private val table = mapOf(
            (WildTrainerTier.NORMAL to WildTrainerPhase.EARLY) to WildTrainerQuality(0..15, 0, 0.0, 0.35, 0.0, 0, 1, 0.10, 1),
            (WildTrainerTier.NORMAL to WildTrainerPhase.MID) to WildTrainerQuality(4..20, 0, 0.15, 0.5, 0.10, 70, 1, 0.25, 2),
            (WildTrainerTier.NORMAL to WildTrainerPhase.LATE) to WildTrainerQuality(8..24, 128, 0.30, 0.6, 0.25, 90, 1, 0.40, 2),
            (WildTrainerTier.NORMAL to WildTrainerPhase.END) to WildTrainerQuality(10..27, 256, 0.40, 0.7, 0.35, 100, 1, 0.50, 3),
            (WildTrainerTier.ACE to WildTrainerPhase.EARLY) to WildTrainerQuality(8..22, 64, 0.50, 0.7, 0.15, 60, 1, 0.35, 3),
            (WildTrainerTier.ACE to WildTrainerPhase.MID) to WildTrainerQuality(12..26, 192, 0.70, 0.8, 0.35, 80, 1, 0.60, 3),
            (WildTrainerTier.ACE to WildTrainerPhase.LATE) to WildTrainerQuality(16..30, 320, 0.85, 0.85, 0.50, 100, 2, 0.80, 4),
            (WildTrainerTier.ACE to WildTrainerPhase.END) to WildTrainerQuality(20..31, 510, 0.90, 0.9, 0.60, 120, 2, 0.90, 4),
        )

        fun of(tier: WildTrainerTier, cap: Int): WildTrainerQuality = table.getValue(tier to WildTrainerPhase.of(cap))
    }
}

/** The stats a Pokemon's build cares about, by their Cobblemon names. */
enum class WildTrainerStat { HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED }

/** What a Pokemon is, as far as building it goes: its types, base stats and whether it can still evolve. */
data class WildTrainerMon(val types: List<String>, val baseStats: Map<WildTrainerStat, Int>, val canEvolve: Boolean) {
    private fun stat(stat: WildTrainerStat) = baseStats[stat] ?: 0

    /** Whether it hits harder physically; ties go physical. */
    val physical: Boolean get() = stat(WildTrainerStat.ATTACK) >= stat(WildTrainerStat.SPECIAL_ATTACK)
    val fast: Boolean get() = stat(WildTrainerStat.SPEED) >= 80
}

enum class WildTrainerMoveCategory { PHYSICAL, SPECIAL, STATUS }

/** A move a Pokemon could know; [taught] marks TM and tutor moves, which the build adds only sometimes. */
data class WildTrainerMove(
    val name: String,
    val type: String,
    val category: WildTrainerMoveCategory,
    val power: Double,
    val accuracy: Double,
    val taught: Boolean = false,
)

/**
 * Builds a wild trainer's Pokemon from its [WildTrainerQuality]: which moves it knows, its nature, IVs, EVs and held
 * item. Everything stays within what a raised Pokemon could have, and some things are never given at all so no
 * trainer turns into a wall: one-hit KO, evasion, self-KO and recharge moves, sleep that never misses, choice items,
 * Focus Sash, and luck items. A party never holds two of the same item.
 */
object WildTrainerBuild {
    const val MOVE_SLOTS = 4

    /** Moves no wild trainer uses. */
    val bannedMoves = setOf(
        "fissure", "sheercold", "horndrill", "guillotine",
        "doubleteam", "minimize", "sandattack", "smokescreen", "kinesis", "flash", "mudslap",
        "explosion", "selfdestruct", "mistyexplosion", "memento", "finalgambit", "healingwish", "lunardance", "destinybond", "perishsong",
        "hyperbeam", "gigaimpact", "blastburn", "hydrocannon", "frenzyplant", "rockwrecker", "roaroftime", "prismaticlaser",
        "eternabeam", "meteorassault",
        "spore", "darkvoid", "shellsmash", "bellydrum", "geomancy", "tailglow", "cottonguard", "acupressure",
        "struggle", "splash", "celebrate", "holdhands", "happyhour", "teleport",
    )

    /** Moves that spend a turn charging; kept only when nothing better fits. */
    private val chargingMoves = setOf("solarbeam", "solarblade", "skyattack", "razorwind", "skullbash", "meteorbeam", "freezeshock", "iceburn",
        "futuresight", "doomdesire", "dig", "fly", "dive", "bounce", "phantomforce", "shadowforce")

    /** Status moves worth a slot, with how much a trainer wants each. Anything else scores as filler. */
    private val usefulStatus = mapOf(
        "swordsdance" to 3.0, "nastyplot" to 3.0, "calmmind" to 2.8, "bulkup" to 2.6, "dragondance" to 3.0, "quiverdance" to 3.0,
        "workup" to 1.8, "growth" to 1.8, "agility" to 1.6, "irondefense" to 1.4, "amnesia" to 1.4, "honeclaws" to 1.6, "coil" to 2.2,
        "thunderwave" to 2.4, "willowisp" to 2.4, "toxic" to 2.2, "poisonpowder" to 1.2, "stunspore" to 1.6, "glare" to 2.2,
        "leechseed" to 2.0, "confuseray" to 1.6, "taunt" to 1.2, "encore" to 1.2,
        "protect" to 1.4, "detect" to 1.4, "substitute" to 1.6, "reflect" to 1.4, "lightscreen" to 1.4,
        "recover" to 2.6, "roost" to 2.6, "softboiled" to 2.6, "slackoff" to 2.6, "milkdrink" to 2.6, "synthesis" to 2.2,
        "moonlight" to 2.2, "morningsun" to 2.2, "shoreup" to 2.4, "wish" to 1.8, "rest" to 1.0,
        "sleeppowder" to 1.8, "hypnosis" to 1.4, "sing" to 1.2, "yawn" to 1.4, "grasswhistle" to 1.0,
        "stealthrock" to 1.6, "spikes" to 1.2, "toxicspikes" to 1.2, "rapidspin" to 1.0, "defog" to 0.8,
    )
    private const val FILLER_STATUS = 0.3

    /** One type-boosting item per type. */
    private val typeBoosters = mapOf(
        "normal" to "silk_scarf", "fire" to "charcoal_stick", "water" to "mystic_water", "grass" to "miracle_seed",
        "electric" to "magnet", "ice" to "never_melt_ice", "fighting" to "black_belt", "poison" to "poison_barb",
        "ground" to "soft_sand", "flying" to "sharp_beak", "psychic" to "twisted_spoon", "bug" to "silver_powder",
        "rock" to "hard_stone", "ghost" to "spell_tag", "dragon" to "dragon_fang", "dark" to "black_glasses",
        "steel" to "metal_coat", "fairy" to "fairy_feather",
    )

    /** Items no wild trainer holds. */
    val bannedItems = setOf("choice_band", "choice_specs", "choice_scarf", "focus_sash", "focus_band", "bright_powder", "quick_claw",
        "kings_rock", "razor_fang", "lax_incense", "weakness_policy")

    // --- Moves -------------------------------------------------------------------------------------------------------

    /** Picks up to four moves from [known] (level-up moves up to its level) and [taught] (TM and tutor moves). */
    fun moves(mon: WildTrainerMon, known: List<WildTrainerMove>, taught: List<WildTrainerMove>, quality: WildTrainerQuality, random: Random): List<String> {
        val teachable = if (random.nextDouble() < quality.tmMoves) {
            taught.filter { it.category == WildTrainerMoveCategory.STATUS || it.power <= quality.tmPowerCap }
        } else emptyList()
        val pool = (known + teachable.map { it.copy(taught = true) })
            .filter { it.name !in bannedMoves }
            .distinctBy { it.name }
        if (pool.isEmpty()) return emptyList()
        val chosen = ArrayList<WildTrainerMove>()
        val slots = minOf(MOVE_SLOTS, pool.size)
        repeat(slots) { slot ->
            val left = pool.filter { it !in chosen && allowed(it, chosen, quality) }
            if (left.isEmpty()) return@repeat
            chosen += if (random.nextDouble() < quality.smartMoves) best(mon, left, chosen, slot, random) else left.random(random)
        }
        // A trainer always has something that deals damage when it can know one.
        if (chosen.none { it.category != WildTrainerMoveCategory.STATUS }) {
            pool.filter { it.category != WildTrainerMoveCategory.STATUS }.maxByOrNull { attackScore(mon, it) }?.let { attack ->
                if (chosen.size >= slots) chosen.removeAt(chosen.lastIndex)
                chosen += attack
            }
        }
        return chosen.map(WildTrainerMove::name)
    }

    private fun allowed(move: WildTrainerMove, chosen: List<WildTrainerMove>, quality: WildTrainerQuality): Boolean =
        move.category != WildTrainerMoveCategory.STATUS || chosen.count { it.category == WildTrainerMoveCategory.STATUS } < quality.maxStatusMoves

    /** How good [move] is as an attack for [mon]: power by accuracy, with STAB and its better attacking stat. */
    fun attackScore(mon: WildTrainerMon, move: WildTrainerMove): Double {
        if (move.category == WildTrainerMoveCategory.STATUS) return 0.0
        val accuracy = if (move.accuracy <= 0) 1.0 else move.accuracy / 100.0
        val stab = if (move.type in mon.types) 1.5 else 1.0
        val side = if ((move.category == WildTrainerMoveCategory.PHYSICAL) == mon.physical) 1.0 else 0.65
        val charging = if (move.name in chargingMoves) 0.5 else 1.0
        return move.power.coerceAtLeast(0.0) * accuracy * stab * side * charging
    }

    private fun statusScore(move: WildTrainerMove, mon: WildTrainerMon): Double {
        val base = usefulStatus[move.name] ?: FILLER_STATUS
        // Setting up with the wrong stat is wasted.
        return when (move.name) {
            "swordsdance", "bulkup", "dragondance", "honeclaws" -> if (mon.physical) base else base * 0.3
            "nastyplot", "calmmind", "quiverdance" -> if (mon.physical) base * 0.3 else base
            else -> base
        }
    }

    /**
     * The move a thoughtful trainer puts in [slot]: a strong STAB attack first, then an attack of another type,
     * then a useful status move, then whatever attack covers the most.
     */
    private fun best(mon: WildTrainerMon, left: List<WildTrainerMove>, chosen: List<WildTrainerMove>, slot: Int, random: Random): WildTrainerMove {
        val attackTypes = chosen.filter { it.category != WildTrainerMoveCategory.STATUS }.map { it.type }.toSet()
        fun score(move: WildTrainerMove): Double {
            val noise = 0.9 + random.nextDouble() * 0.2
            if (move.category == WildTrainerMoveCategory.STATUS) {
                val want = if (slot == 2) 60.0 else 25.0
                return statusScore(move, mon) * want * noise
            }
            val attack = attackScore(mon, move)
            val repeatsType = move.type in attackTypes
            val newType = when {
                slot == 0 -> if (move.type in mon.types) 1.2 else 0.9
                repeatsType -> 0.45
                else -> 1.15
            }
            return attack * newType * noise
        }
        return left.maxBy(::score)
    }

    // --- Nature, IVs and EVs -----------------------------------------------------------------------------------------

    /** A nature that suits [mon] (by its Cobblemon name), or null to keep the random one it was born with. */
    fun nature(mon: WildTrainerMon, quality: WildTrainerQuality, random: Random): String? {
        if (random.nextDouble() >= quality.fittedNature) return null
        val choices = when {
            mon.physical && mon.fast -> listOf("jolly", "adamant")
            mon.physical -> listOf("adamant", "adamant", "brave", "impish")
            mon.fast -> listOf("timid", "modest")
            else -> listOf("modest", "modest", "quiet", "calm")
        }
        return choices.random(random)
    }

    fun ivs(quality: WildTrainerQuality, random: Random): Map<WildTrainerStat, Int> =
        WildTrainerStat.entries.associateWith { random.nextInt(quality.ivs.first, quality.ivs.last + 1) }

    /** Up to [WildTrainerQuality.evTotal] effort values: most in its attacking stat, then speed or HP, the rest HP. */
    fun evs(mon: WildTrainerMon, quality: WildTrainerQuality): Map<WildTrainerStat, Int> {
        val total = quality.evTotal.coerceIn(0, 510)
        if (total == 0) return emptyMap()
        val attack = if (mon.physical) WildTrainerStat.ATTACK else WildTrainerStat.SPECIAL_ATTACK
        val second = if (mon.fast) WildTrainerStat.SPEED else WildTrainerStat.HP
        val third = if (second == WildTrainerStat.HP) WildTrainerStat.DEFENCE else WildTrainerStat.HP
        val first = minOf(252, ceil(total / 2.0).toInt())
        val next = minOf(252, ((total - first) * 0.6).toInt())
        val last = minOf(252, total - first - next)
        return mapOf(attack to first, second to next, third to last).filterValues { it > 0 }
    }

    // --- Held item ---------------------------------------------------------------------------------------------------

    /**
     * The item [mon] holds (a Cobblemon item path), or null. [attackType] is the type of its strongest attack;
     * [taken] holds the items the party already carries.
     */
    fun item(mon: WildTrainerMon, attackType: String?, tier: WildTrainerTier, cap: Int, taken: Set<String>, random: Random): String? {
        val quality = WildTrainerQuality.of(tier, cap)
        if (random.nextDouble() >= quality.itemChance) return null
        val ace = tier == WildTrainerTier.ACE
        val booster = attackType?.let(typeBoosters::get)
        val weighted = buildList {
            when (WildTrainerPhase.of(cap)) {
                WildTrainerPhase.EARLY -> {
                    add("oran_berry" to 4)
                    if (ace) add("sitrus_berry" to 2)
                }
                WildTrainerPhase.MID -> {
                    add("oran_berry" to 2); add("sitrus_berry" to 3)
                    add("cheri_berry" to 1); add("rawst_berry" to 1); add("pecha_berry" to 1); add("chesto_berry" to 1)
                    booster?.let { add(it to 3) }
                    if (ace && mon.canEvolve) add("eviolite" to 2)
                }
                WildTrainerPhase.LATE -> {
                    add("sitrus_berry" to 3); add("lum_berry" to 2)
                    booster?.let { add(it to 3) }
                    if (ace) {
                        add("leftovers" to 2); add("expert_belt" to 1); add("rocky_helmet" to 1)
                        add((if (mon.physical) "muscle_band" else "wise_glasses") to 1)
                        if (mon.canEvolve) add("eviolite" to 3)
                    }
                }
                WildTrainerPhase.END -> {
                    add("sitrus_berry" to 2); add("lum_berry" to 2); add("leftovers" to 2)
                    booster?.let { add(it to 2) }
                    if (ace) {
                        add("life_orb" to 1); add("assault_vest" to 1); add("expert_belt" to 1); add("rocky_helmet" to 1)
                        add((if (mon.physical) "muscle_band" else "wise_glasses") to 1)
                        if (mon.canEvolve) add("eviolite" to 3)
                    } else {
                        add("shell_bell" to 1)
                    }
                }
            }
        }.filter { (item, _) -> item !in taken && item !in bannedItems }
        if (weighted.isEmpty()) return null
        var roll = random.nextInt(weighted.sumOf { it.second })
        for ((item, weight) in weighted) {
            if (roll < weight) return item
            roll -= weight
        }
        return null
    }
}
