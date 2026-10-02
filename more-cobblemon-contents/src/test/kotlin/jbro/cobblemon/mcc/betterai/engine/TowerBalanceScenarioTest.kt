package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import jbro.cobblemon.mcc.betterai.engine.sim.fork
import jbro.cobblemon.mcc.internal.battle.LegendaryClassPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.math.pow

/**
 * Battle Tower balance scenario on the AI engine: single 3 vs 3 battles at level 50 against teams drawn from the
 * Tower's own tier 2 Tera sets (the sets every regular opponent from the 6th win on uses), with several player teams.
 * Both sides play the same greedy policy: try each move in a forked battle against a random reply and use the one
 * that dealt the most damage. So the win rates compare teams, not players; the Tower's real AI plays better than
 * random replies but not like a person. Report: build/reports/tower-balance.md.
 *
 *     ./gradlew :more-cobblemon-contents:unitTest -Pscope=engine -Ptests=TowerBalanceScenario -Psweeps
 */
@EnabledIfSystemProperty(named = "betterai.sweeps", matches = "true")
class TowerBalanceScenarioTest {
    private val dex = EngineReferee.dex
    private val towerSets: List<JsonObject> by lazy {
        val directory = Path.of("../more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets")
        Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".json") }.sorted().toList() }.flatMap { path ->
            JsonParser.parseString(Files.readString(path)).asJsonObject.getAsJsonArray("pokemon_sets").map { it.asJsonObject }
        }
    }

    private fun id(value: String) = Js.toID(value.substringAfter(':'))

    private val statKeys = mapOf("hp" to "hp", "attack" to "atk", "defense" to "def", "special_attack" to "spa",
        "special_defense" to "spd", "speed" to "spe")

    /** A Tower set as the engine reads it; [trained] gives it 31 IVs and a full attack and speed spread instead. */
    private fun refSet(set: JsonObject, trained: Boolean): RefSet? {
        val name = id(set["species_id"].asString) + (set["form_id"]?.takeUnless { it.isJsonNull }?.asString?.let(::id) ?: "")
        val species = dex.species(name) ?: return null
        val moves = set.getAsJsonArray("moves").map { id(it.asString) }.filter { dex.move(it) != null }
        if (moves.isEmpty()) return null
        val nature = id(set["nature_id"].asString).replaceFirstChar { it.uppercase() }
        val evs = if (trained) {
            val attack = if ((species.baseStats["atk"] ?: 0) >= (species.baseStats["spa"] ?: 0)) "atk" else "spa"
            mapOf(attack to 252, "spe" to 252, "hp" to 4)
        } else set.getAsJsonObject("evs").entrySet().associate { statKeys.getValue(it.key) to it.value.asInt }
        val ivs = if (trained) emptyMap() else set.getAsJsonObject("ivs").entrySet().associate { statKeys.getValue(it.key) to it.value.asInt }
        return RefSet(species.name, moves, ability = id(set["ability_id"].asString), item = id(set["held_item_id"].asString),
            nature = nature, evs = evs, ivs = ivs, gender = species.gender.ifEmpty { "M" })
    }

    private fun pool(legendaryAllowed: Boolean, tier: Int = 2): List<JsonObject> = towerSets.filter { set ->
        set["mechanic_id"].asString == "tera" && set["set_tier"].asInt == tier &&
            (legendaryAllowed || LegendaryClassPolicy.categoryFor(set["species_id"].asString) == null)
    }

    private fun randomTeam(random: Random, pool: List<JsonObject>, trained: Boolean): List<RefSet> {
        val team = LinkedHashMap<String, RefSet>()
        while (team.size < 3) {
            val set = pool[random.nextInt(pool.size)]
            val ref = refSet(set, trained) ?: continue
            val base = dex.species(ref.species)!!.baseSpecies
            if (team.keys.none { it == base } && team.values.none { it.item == ref.item }) team[base] = ref
        }
        return team.values.toList()
    }

    private val championAces = mapOf("blue" to "cobblemon:blastoise", "lance" to "cobblemon:dragonite", "cynthia" to "cobblemon:garchomp")

    /**
     * A Champion's Tera team the way the Tower draws one against a challenger without legendaries: the ace and two
     * other members, each one of its sets, with IVs of [iv] (25 for the first Champion, 31 from the 20th win).
     */
    private fun championTeam(random: Random, iv: Int): List<RefSet> {
        val name = championAces.keys.toList()[random.nextInt(championAces.size)]
        val sets = towerSets.filter {
            it["set_id"].asString.startsWith("champion_${name}_tera_") && LegendaryClassPolicy.categoryFor(it["species_id"].asString) == null
        }.groupBy { it["species_id"].asString }
        val ace = championAces.getValue(name)
        val order = listOf(ace) + (sets.keys - ace).shuffled(random)
        val team = ArrayList<RefSet>()
        for (species in order) {
            if (team.size == 3) break
            val variants = sets.getValue(species)
            val ref = refSet(variants[random.nextInt(variants.size)], trained = false)?.copy(ivs = RefSet.STATS.associateWith { iv }) ?: continue
            if (team.none { it.item == ref.item }) team += ref
        }
        return team
    }

    private fun fullyTrained(species: String, moves: List<String>, ability: String, item: String, nature: String, attack: String) =
        RefSet(dex.species(species)!!.name, moves, ability = ability, item = item, nature = nature,
            evs = mapOf(attack to 252, "spe" to 252, "hp" to 4), gender = dex.species(species)!!.gender.ifEmpty { "M" })

    /** Strong, ordinary picks a player brings to break a facility: no legendary class. */
    private val firepower = listOf(
        fullyTrained("dragonite", listOf("extremespeed", "outrage", "earthquake", "firepunch"), "multiscale", "choiceband", "Adamant", "atk"),
        fullyTrained("kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "supremeoverlord", "lifeorb", "Adamant", "atk"),
        fullyTrained("gholdengo", listOf("makeitrain", "shadowball", "focusblast", "nastyplot"), "goodasgold", "choicespecs", "Modest", "spa"),
    )

    /** The same with restricted legendaries, for the Tower's legendary class rule. */
    private val legendaryFirepower = listOf(
        fullyTrained("calyrexshadow", listOf("astralbarrage", "psyshock", "nastyplot", "protect"), "asonespectrier", "lifeorb", "Timid", "spa"),
        fullyTrained("koraidon", listOf("collisioncourse", "flareblitz", "outrage", "uturn"), "orichalcumpulse", "choiceband", "Jolly", "atk"),
        fullyTrained("miraidon", listOf("electrodrift", "dracometeor", "voltswitch", "dazzlinggleam"), "hadronengine", "choicespecs", "Timid", "spa"),
    )

    private fun legalMoves(battle: Battle, side: Side): List<Int> {
        val data = side.activeRequest?.active?.firstOrNull() ?: return emptyList()
        return data.moves.withIndex().filter { !Js.truthy(it.value.disabled) }.map { it.index + 1 }
    }

    private fun benchSwitch(side: Side): String? =
        (side.active.size until side.pokemon.size).firstOrNull { !side.pokemon[it].fainted }?.let { "switch ${it + 1}" }

    private fun randomChoice(random: Random, battle: Battle, side: Side): String {
        val request = side.activeRequest ?: return ""
        if (request.wait) return ""
        if (request.forceSwitch != null) return benchSwitch(side) ?: "pass"
        return legalMoves(battle, side).takeIf { it.isNotEmpty() }?.let { "move ${it[random.nextInt(it.size)]}" } ?: "move 1"
    }

    /** The move that dealt the most damage over two forked tries against random replies. */
    private fun greedyChoice(random: Random, battle: Battle, side: Side): String {
        val request = side.activeRequest ?: return ""
        if (request.wait) return ""
        if (request.forceSwitch != null) return benchSwitch(side) ?: "pass"
        val moves = legalMoves(battle, side)
        if (moves.size <= 1) return "move ${moves.firstOrNull() ?: 1}"
        val sideIndex = battle.sides.indexOf(side)
        return "move " + moves.maxBy { move ->
            (0 until 2).sumOf {
                val copy = battle.fork()
                val mine = copy.sides[sideIndex]
                val foe = copy.sides[1 - sideIndex]
                val target = foe.active[0] ?: return@sumOf 0.0
                val before = target.hp.toDouble() / target.maxhp
                if (!mine.choose("move $move")) return@sumOf -1.0
                randomChoice(random, copy, foe).takeIf { it.isNotEmpty() }?.let { if (!foe.choose(it)) foe.choose("default") }
                runCatching { copy.commitDecisions() }
                val after = if (target.fainted) 0.0 else target.hp.toDouble() / target.maxhp
                (before - after) + if (target.fainted) 0.5 else 0.0
            }
        }
    }

    /** True when [player] beats [opponent]. */
    private fun play(player: List<RefSet>, opponent: List<RefSet>, random: Random): Boolean {
        val battle = Battle(dex, BattleOptions(gameType = "singles", seed = IntArray(4) { random.nextInt(65536) }, log = false))
        battle.setPlayer("p1", "p1", player.mapIndexed { i, s -> s.toSet("p1", i) })
        battle.setPlayer("p2", "p2", opponent.mapIndexed { i, s -> s.toSet("p2", i) })
        var steps = 0
        while (!battle.ended && steps++ < 150) {
            battle.sides.forEach { side ->
                val choice = greedyChoice(random, battle, side)
                if (choice.isNotEmpty() && !runCatching { side.choose(choice) }.getOrDefault(false)) side.choose("default")
            }
            runCatching { battle.commitDecisions() }.onFailure { return false }
        }
        return battle.winner == "p1"
    }

    @Test
    fun `how far a strong team runs through the Tower's regular opponents`() {
        val battles = 200
        val report = StringBuilder("# Battle Tower balance scenario\n\nSingle 3 vs 3, $battles battles per cell, Tera sets, " +
            "both sides on the same greedy policy (so the Tower's AI levels are not modelled). The challenger stays at " +
            "level 50. Opponents by stage: tier 1 (IV 15, 0 EVs) wins 1 to 5, tier 2 (IV 20, 252 EVs) 6 to 10, tier 3 " +
            "(IV 25, 384 EVs) 11 to 20, tier 4 (IV 31, 508 EVs) from 21; Champions at every 10th win (IV 25 the first " +
            "time, 31 after). From the 21st win opponents are one level higher each win (51, 52, ...); the reach columns " +
            "use the level of each win, interpolating between the measured levels.\n\n")
        fun at(level: Int, team: List<RefSet>) = team.map { it.copy(level = level) }
        val climbLevels = listOf(50, 55, 60, 70, 79)
        val opponents = ArrayList<Pair<String, (Random) -> List<RefSet>>>()
        opponents += "tier 1" to { r: Random -> randomTeam(r, pool(false, 1), trained = false) }
        opponents += "tier 2" to { r: Random -> randomTeam(r, pool(false, 2), trained = false) }
        opponents += "tier 3" to { r: Random -> randomTeam(r, pool(false, 3), trained = false) }
        opponents += "Champion Lv50 IV25" to { r: Random -> championTeam(r, 25) }
        opponents += "Champion Lv50" to { r: Random -> championTeam(r, 31) }
        opponents += "Champion Lv60" to { r: Random -> at(60, championTeam(r, 31)) }
        opponents += "Champion Lv70" to { r: Random -> at(70, championTeam(r, 31)) }
        climbLevels.forEach { level -> opponents += "tier 4 Lv$level" to { r: Random -> at(level, randomTeam(r, pool(false, 4), trained = false)) } }
        val players: List<Pair<String, (Random) -> List<RefSet>>> = listOf(
            "random species, tier 2 sets" to { r -> randomTeam(r, pool(false, 2), trained = false) },
            "random species, fully trained" to { r -> randomTeam(r, pool(false, 2), trained = true) },
            "Dragonite, Kingambit, Gholdengo" to { _ -> firepower },
        )
        report.append("| player team | " + opponents.joinToString(" | ") { it.first } + " | reach 10 | reach 20 | reach 30 | reach 49 |\n")
        report.append("|---|" + opponents.joinToString("") { "---|" } + "---|---|---|---|\n")
        players.forEachIndexed { playerIndex, (playerName, player) ->
            val rates = opponents.mapIndexed { opponentIndex, (_, opponent) ->
                val random = Random(20261002L + playerIndex * 31L + opponentIndex)
                var wins = 0
                repeat(battles) { if (play(player(random), opponent(random), random)) wins++ }
                wins.toDouble() / battles
            }
            val climb = climbLevels.indices.map { rates[7 + it] }
            /** Tier 4 at [level], read off the measured levels in a straight line between them. */
            fun tier4(level: Int): Double {
                val upper = climbLevels.indexOfFirst { it >= level }.takeIf { it >= 0 } ?: return climb.last()
                if (upper == 0) return climb.first()
                val (lo, hi) = climbLevels[upper - 1] to climbLevels[upper]
                return climb[upper - 1] + (climb[upper] - climb[upper - 1]) * (level - lo) / (hi - lo)
            }
            // The opponent of each win: tiers by stage, a Champion every 10th (the Aces between fight with their stage's sets).
            fun rateFor(win: Int): Double = when {
                win == 10 -> rates[3]
                win == 20 -> rates[4]
                win == 30 -> rates[5]
                win == 40 -> rates[6]
                win <= 5 -> rates[0]
                win <= 10 -> rates[1]
                win <= 20 -> rates[2]
                else -> tier4(TOWER_LEVEL + win - 20)
            }
            fun reach(wins: Int) = (1..wins).fold(1.0) { p, win -> p * rateFor(win) }
            report.append("| $playerName | " + rates.joinToString(" | ") { "%.1f%%".format(it * 100) } + " | " +
                listOf(10, 20, 30, 49).joinToString(" | ") { "%.1f%%".format(reach(it) * 100) } + " |\n")
        }
        run {
            val random = Random(20261003L)
            var wins = 0
            repeat(battles) { if (play(legendaryFirepower, randomTeam(random, pool(true, 4), trained = false), random)) wins++ }
            report.append("\nCalyrex-Shadow, Koraidon and Miraidon against tier 4 with the legendary class on: " +
                "%.1f%%".format(wins * 100.0 / battles) + "\n")
        }
        val out = Path.of("build/reports/tower-balance.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
        println(report)
    }
}

private const val TOWER_LEVEL = 50
