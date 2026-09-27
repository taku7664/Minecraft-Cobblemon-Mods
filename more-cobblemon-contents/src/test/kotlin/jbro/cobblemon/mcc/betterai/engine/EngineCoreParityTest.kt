package jbro.cobblemon.mcc.betterai.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine's battle core against the dev server's Showdown: same teams, same seeds, same choices, and the
 * protocol logs must match line for line. Sets carry no abilities or items so only the core is exercised.
 */
class EngineCoreParityTest {
    private val seeds = listOf(intArrayOf(1, 2, 3, 4), intArrayOf(9, 8, 7, 6), intArrayOf(12345, 54321, 111, 222),
        intArrayOf(40000, 3, 65535, 17), intArrayOf(7, 7, 7, 7))

    private fun sweep(base: RefScenario) = seeds.map { base.withSeed(it) }

    private fun assertParity(scenarios: List<RefScenario>) {
        val differences = EngineReferee.compare(scenarios)
        assertTrue(differences.isEmpty()) { differences.take(3).joinToString("\n") }
    }

    @Test
    fun `plain attacks, crits and damage rolls match`() = assertParity(sweep(RefScenario(
        "tackle-exchange",
        listOf(RefSet("Pikachu", listOf("tackle", "thunderbolt"))),
        listOf(RefSet("Bulbasaur", listOf("tackle", "vinewhip"))),
        listOf("move 1" to "move 1", "move 2" to "move 2", "move 2" to "move 1", "move 1" to "move 2"),
    )))

    @Test
    fun `type effectiveness and immunity match`() = assertParity(sweep(RefScenario(
        "effectiveness",
        listOf(RefSet("Garchomp", listOf("earthquake", "dragonclaw", "stoneedge", "firefang"))),
        listOf(RefSet("Gyarados", listOf("waterfall", "icefang", "bounce", "tackle")), RefSet("Magnezone", listOf("thunderbolt", "flashcannon"))),
        listOf("move 1" to "move 1", "move 3" to "move 2", "move 4" to "switch 2", "move 1" to "move 1", "move 1" to "move 2"),
    )))

    @Test
    fun `switching, fainting and forced replacement match`() = assertParity(sweep(RefScenario(
        "switch-faint",
        listOf(RefSet("Machamp", listOf("closecombat", "knockoff"), level = 100), RefSet("Snorlax", listOf("bodyslam", "earthquake"))),
        listOf(RefSet("Rattata", listOf("tackle"), level = 5), RefSet("Raticate", listOf("tackle", "quickattack"), level = 20),
            RefSet("Blissey", listOf("seismictoss", "pound"))),
        listOf("move 1" to "move 1", "" to "switch 2", "switch 2" to "move 2", "move 1" to "move 1", "" to "switch 3",
            "move 2" to "move 1", "move 1" to "move 2"),
    )))

    @Test
    fun `status moves and residual damage match`() = assertParity(sweep(RefScenario(
        "status-residual",
        listOf(RefSet("Gengar", listOf("willowisp", "toxic", "hypnosis", "confuseray"))),
        listOf(RefSet("Tyranitar", listOf("crunch", "thunderwave", "sandstorm", "stoneedge")), RefSet("Snorlax", listOf("bodyslam", "tackle"))),
        listOf("move 1" to "move 2", "move 2" to "move 3", "move 3" to "switch 2", "move 4" to "move 1", "move 2" to "move 1",
            "move 1" to "move 2", "move 3" to "move 1"),
    )))

    @Test
    fun `terastallization matches`() = assertParity(sweep(RefScenario(
        "tera",
        listOf(RefSet("Dragonite", listOf("extremespeed", "dragonclaw", "earthquake"), teraType = "Normal"),
            RefSet("Garchomp", listOf("earthquake", "dragonclaw"), teraType = "Steel")),
        listOf(RefSet("Gholdengo", listOf("makeitrain", "shadowball", "flashcannon"), teraType = "Water"),
            RefSet("Kingambit", listOf("ironhead", "kowtowcleave"), teraType = "Stellar")),
        listOf("move 1 terastallize" to "move 2 terastallize", "move 2" to "move 3", "switch 2" to "switch 2",
            "move 1" to "move 1", "move 2" to "move 2"),
    )))

    @Test
    fun `mega evolution matches`() = assertParity(sweep(RefScenario(
        "mega",
        listOf(RefSet("Charizard", listOf("flamethrower", "airslash", "dragonclaw"), item = "Charizardite Y")),
        listOf(RefSet("Garchomp", listOf("earthquake", "stoneedge"), item = "Garchompite"), RefSet("Blissey", listOf("tackle"))),
        listOf("move 1 mega" to "move 2 mega", "move 2" to "move 1", "move 3" to "move 2", "move 1" to "move 1"),
    )))

    @Test
    fun `dynamax matches`() = assertParity(sweep(RefScenario(
        "dynamax",
        listOf(RefSet("Snorlax", listOf("bodyslam", "earthquake", "crunch")), RefSet("Chansey", listOf("tackle"))),
        listOf(RefSet("Gyarados", listOf("waterfall", "icefang", "bounce")), RefSet("Blissey", listOf("tackle"))),
        listOf("move 1 dynamax" to "move 1", "move 2" to "move 2 dynamax", "move 3" to "move 3", "move 1" to "move 1",
            "move 2" to "move 1", "move 1" to "move 2"),
    )))

    @Test
    fun `doubles targeting and spread moves match`() = assertParity(sweep(RefScenario(
        "doubles",
        listOf(RefSet("Garchomp", listOf("earthquake", "dragonclaw", "rockslide")), RefSet("Pelipper", listOf("hurricane", "surf", "icebeam")),
            RefSet("Snorlax", listOf("bodyslam"))),
        listOf(RefSet("Heatran", listOf("heatwave", "earthpower", "flashcannon")), RefSet("Amoonguss", listOf("sludgebomb", "gigadrain")),
            RefSet("Blissey", listOf("tackle"))),
        listOf("move 1, move 2 2" to "move 1, move 2 1", "move 2 1, move 3 2" to "move 2 2, move 1 1",
            "move 3, move 1 1" to "move 3 1, move 2 2", "move 1, move 2" to "move 1, move 1 1"),
        gameType = "doubles",
    )))

    @Test
    fun `speed ties and priority match`() = assertParity(sweep(RefScenario(
        "speed-tie",
        listOf(RefSet("Dragonite", listOf("extremespeed", "dragonclaw"))),
        listOf(RefSet("Dragonite", listOf("extremespeed", "dragonclaw"))),
        listOf("move 2" to "move 2", "move 1" to "move 2", "move 1" to "move 1", "move 2" to "move 2"),
    )))
}
