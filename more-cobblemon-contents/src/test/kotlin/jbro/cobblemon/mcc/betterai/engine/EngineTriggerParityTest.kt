package jbro.cobblemon.mcc.betterai.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Battles built to make the most used effects actually fire (the generic sweep may never trigger them):
 * each scenario sets up the condition an effect waits for, then Showdown and the engine must log the
 * same thing under several seeds.
 */
class EngineTriggerParityTest {
    private val seeds = listOf(intArrayOf(1, 2, 3, 4), intArrayOf(9, 8, 7, 6), intArrayOf(31, 41, 59, 26), intArrayOf(2718, 2818, 2845, 9045))

    private fun check(vararg scenarios: RefScenario) {
        val all = scenarios.flatMap { s -> seeds.map { s.withSeed(it) } }
        val differences = EngineReferee.compare(all)
        assertTrue(differences.isEmpty()) { differences.take(3).joinToString("\n") }
    }

    private fun mon(species: String, vararg moves: String, ability: String = "", item: String = "", level: Int = 50,
                    tera: String? = null, evs: Map<String, Int> = emptyMap()) =
        RefSet(species, moves.toList(), ability = ability, item = item, level = level, teraType = tera, evs = evs)

    @Test
    fun `protection, fake out, sucker punch and taunt fire`() = check(
        RefScenario("protect-fakeout",
            listOf(mon("Incineroar", "fakeout", "knockoff", "partingshot", "protect", ability = "Intimidate", item = "Sitrus Berry"),
                mon("Amoonguss", "spore", "ragepowder", "gigadrain", "protect", ability = "Regenerator")),
            listOf(mon("Kingambit", "suckerpunch", "kowtowcleave", "protect", "swordsdance", ability = "Defiant", item = "Black Glasses"),
                mon("Gholdengo", "makeitrain", "shadowball", "nastyplot", "protect", ability = "Good as Gold")),
            listOf("move 1" to "move 3", "move 1" to "move 1", "move 4" to "move 4", "move 2" to "move 1", "move 3" to "move 2",
                "move 4" to "move 1", "move 1" to "switch 2", "move 2" to "move 3")),
        RefScenario("taunt-encore",
            listOf(mon("Tornadus", "taunt", "tailwind", "bleakwindstorm", "encore", ability = "Prankster", item = "Covert Cloak")),
            listOf(mon("Hatterene", "trickroom", "calmmind", "psychic", "drainingkiss", ability = "Magic Bounce"),
                mon("Clefable", "moonblast", "calmmind", "softboiled", "encore", ability = "Unaware", item = "Leftovers")),
            listOf("move 1" to "move 1", "move 2" to "switch 2", "move 4" to "move 2", "move 3" to "move 2", "move 1" to "move 4",
                "move 3" to "move 1")),
    )

    @Test
    fun `trick room, tailwind and speed order fire`() = check(
        RefScenario("trickroom",
            listOf(mon("Porygon2", "trickroom", "triattack", "recover", "icebeam", item = "Eviolite", ability = "Download"),
                mon("Ursaluna", "facade", "headlongrush", "protect", "earthquake", ability = "Guts", item = "Flame Orb")),
            listOf(mon("Dragapult", "dragondarts", "phantomforce", "uturn", "willowisp", item = "Choice Band", ability = "Clear Body"),
                mon("Flutter Mane", "moonblast", "shadowball", "dazzlinggleam", "protect", item = "Booster Energy", ability = "Protosynthesis")),
            listOf("move 1" to "move 3", "move 2" to "switch 2", "switch 2" to "move 1", "move 1" to "move 2", "move 2" to "move 1",
                "move 4" to "move 3")),
    )

    @Test
    fun `survival and consumable items fire`() = check(
        RefScenario("sash-berry",
            listOf(mon("Garchomp", "earthquake", "dragonclaw", "swordsdance", "stoneedge", item = "Life Orb", ability = "Rough Skin",
                evs = mapOf("atk" to 252, "spe" to 252))),
            listOf(mon("Alakazam", "psychic", "focusblast", "shadowball", "calmmind", item = "Focus Sash", ability = "Magic Guard"),
                mon("Blissey", "seismictoss", "softboiled", "thunderwave", "icebeam", item = "Sitrus Berry", ability = "Natural Cure"),
                mon("Dragonite", "extremespeed", "dragondance", "earthquake", "roost", item = "Weakness Policy", ability = "Multiscale")),
            listOf("move 1" to "move 1", "move 1" to "move 2", "move 3" to "switch 3", "move 4" to "move 2", "move 2" to "move 1",
                "move 1" to "move 1", "move 1" to "move 3")),
        RefScenario("helmet-leftovers",
            listOf(mon("Scizor", "bulletpunch", "uturn", "swordsdance", "knockoff", ability = "Technician", item = "Choice Band"),
                mon("Heatran", "magmastorm", "earthpower", "flashcannon", "protect", ability = "Flash Fire", item = "Leftovers")),
            listOf(mon("Ferrothorn", "powerwhip", "leechseed", "stealthrock", "protect", ability = "Iron Barbs", item = "Rocky Helmet"),
                mon("Toxapex", "scald", "toxic", "recover", "haze", ability = "Regenerator", item = "Black Sludge")),
            listOf("move 1" to "move 3", "move 2" to "move 2", "move 1" to "move 1", "switch 2" to "switch 2", "move 1" to "move 2",
                "move 2" to "move 3", "move 3" to "move 1")),
    )

    @Test
    fun `substitute, stealth rock and hazards fire`() = check(
        RefScenario("sub-rocks",
            listOf(mon("Great Tusk", "rapidspin", "headlongrush", "knockoff", "icespinner", ability = "Protosynthesis", item = "Booster Energy"),
                mon("Glimmora", "stealthrock", "mortalspin", "powergem", "earthpower", ability = "Toxic Debris", item = "Focus Sash")),
            listOf(mon("Gengar", "substitute", "shadowball", "sludgebomb", "focusblast", ability = "Cursed Body", item = "Life Orb"),
                mon("Skarmory", "spikes", "roost", "bodypress", "whirlwind", ability = "Sturdy", item = "Rocky Helmet")),
            listOf("switch 2" to "move 1", "move 1" to "move 2", "move 2" to "switch 2", "move 1" to "move 1", "switch 2" to "move 4",
                "move 3" to "move 2", "move 1" to "move 3")),
    )

    @Test
    fun `gravity cancels a move another move calls`() = check(
        RefScenario("gravity-sleeptalk",
            listOf(mon("Snorlax", "rest", "sleeptalk", "fly", "highjumpkick", ability = "Thick Fat", item = "Leftovers")),
            listOf(mon("Porygon2", "gravity", "throatchop", "triattack", "recover", item = "Eviolite", ability = "Download"),
                mon("Exploud", "boomburst", "hypervoice", "sleeptalk", "rest", ability = "Scrappy")),
            // Snorlax is hurt first so Rest puts it to sleep; Sleep Talk then calls High Jump Kick under Gravity.
            listOf("move 4" to "move 3", "move 1" to "move 1", "move 2" to "move 4", "move 2" to "move 3", "move 2" to "move 4",
                "move 2" to "move 3")),
        RefScenario("gravity-sleeptalk-doubles",
            listOf(mon("Snorlax", "rest", "sleeptalk", "highjumpkick", "bodyslam", ability = "Thick Fat"),
                mon("Exploud", "rest", "sleeptalk", "boomburst", "hypervoice", ability = "Scrappy")),
            listOf(mon("Porygon2", "gravity", "throatchop", "triattack", "recover", item = "Eviolite", ability = "Download"),
                mon("Kingambit", "throatchop", "suckerpunch", "ironhead", "protect", ability = "Defiant")),
            listOf("move 3 1, move 3" to "move 3 1, move 3 1", "move 1, move 4" to "move 1, move 4", "move 2, move 4" to "move 4, move 1 2",
                "move 2, move 3" to "move 3 1, move 3 1", "move 2, move 4" to "move 4, move 4"),
            gameType = "doubles"),
    )

    @Test
    fun `terastallization and paradox boosts fire`() = check(
        RefScenario("tera-paradox",
            listOf(mon("Iron Hands", "drainpunch", "wildcharge", "fakeout", "icepunch", ability = "Quark Drive", item = "Assault Vest", tera = "Grass"),
                mon("Pelipper", "hurricane", "hydropump", "uturn", "protect", ability = "Drizzle", item = "Damp Rock")),
            listOf(mon("Torkoal", "eruption", "heatwave", "earthpower", "protect", ability = "Drought", item = "Charcoal", tera = "Fire"),
                mon("Roaring Moon", "dragondance", "knockoff", "acrobatics", "earthquake", ability = "Protosynthesis", item = "Booster Energy", tera = "Flying")),
            listOf("move 3" to "move 1 terastallize", "move 1 terastallize" to "move 2", "switch 2" to "switch 2", "move 1" to "move 1",
                "move 3" to "move 3 terastallize", "move 2" to "move 2")),
    )
}
