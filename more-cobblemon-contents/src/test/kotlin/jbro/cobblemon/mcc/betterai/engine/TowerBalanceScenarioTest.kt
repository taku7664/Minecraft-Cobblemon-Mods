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
import jbro.cobblemon.mcc.api.battle.TeamPreviewSelection
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.battle.LegendaryClassPolicy
import kotlin.math.ln
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.math.pow

/**
 * Battle Tower balance scenario on the AI engine: single 3 vs 3 battles against teams drawn from the Tower's own Tera
 * sets, trained competitively from the first win and one level higher every 5 wins, with several player teams.
 * Both sides play the same greedy policy: try each move in a forked battle against a random reply and use the one
 * that dealt the most damage. So the win rates compare teams, not players; the Tower's real AI plays better than
 * random replies but not like a person. Report: build/reports/tower-balance.md.
 *
 *     ./gradlew :more-cobblemon-contents:unitTest -Pscope=engine -Ptests=TowerBalanceScenario -Psweeps
 */
/** The engine, the Tower's sets, the player teams and the greedy battle loop the Tower scenarios share. */
abstract class TowerScenarioBase {
    protected val dex = EngineReferee.dex
    protected val towerSets: List<JsonObject> by lazy {
        val directory = Path.of("../more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets")
        Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".json") }.sorted().toList() }.flatMap { path ->
            JsonParser.parseString(Files.readString(path)).asJsonObject.getAsJsonArray("pokemon_sets").map { it.asJsonObject }
        }
    }

    protected fun id(value: String) = Js.toID(value.substringAfter(':'))

    protected val statKeys = mapOf("hp" to "hp", "attack" to "atk", "defense" to "def", "special_attack" to "spa",
        "special_defense" to "spd", "speed" to "spe")

    /** A Tower set as the engine reads it; [trained] gives it 31 IVs and a full attack and speed spread instead. */
    protected fun refSet(set: JsonObject, trained: Boolean): RefSet? {
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

    protected fun pool(legendaryAllowed: Boolean, tier: Int = 2): List<JsonObject> = towerSets.filter { set ->
        set["mechanic_id"].asString == "tera" && set["set_tier"].asInt == tier &&
            (legendaryAllowed || LegendaryClassPolicy.categoryFor(set["species_id"].asString) == null)
    }

    protected fun randomTeam(random: Random, pool: List<JsonObject>, trained: Boolean): List<RefSet> {
        val team = LinkedHashMap<String, RefSet>()
        while (team.size < 3) {
            val set = pool[random.nextInt(pool.size)]
            val ref = refSet(set, trained) ?: continue
            val base = dex.species(ref.species)!!.baseSpecies
            if (team.keys.none { it == base } && team.values.none { it.item == ref.item }) team[base] = ref
        }
        return team.values.toList()
    }

    protected val championAces = mapOf(
        "blue" to "cobblemon:blastoise", "lance" to "cobblemon:dragonite", "cynthia" to "cobblemon:garchomp",
        "steven" to "cobblemon:metagross", "wallace" to "cobblemon:milotic", "alder" to "cobblemon:volcarona",
        "iris" to "cobblemon:haxorus", "diantha" to "cobblemon:gardevoir", "geeta" to "cobblemon:glimmora",
        "nemona" to "cobblemon:pawmot", "n" to "cobblemon:zoroark",
    )

    /** The public stat range a Pokemon of [species] at [level] may have: any IVs, EVs and nature. */
    private fun publicStats(species: jbro.cobblemon.mcc.betterai.engine.dex.Species, level: Int): BattleCombatStatRangesView {
        fun range(stat: String): BattleIntegerRange {
            val base = species.baseStats[stat] ?: 50
            fun value(iv: Int, ev: Int, nature: Double): Int {
                val core = (2 * base + iv + ev / 4) * level / 100
                return if (stat == "hp") core + level + 10 else ((core + 5) * nature).toInt()
            }
            return if (stat == "hp") BattleIntegerRange(value(0, 0, 1.0), value(31, 252, 1.0))
            else BattleIntegerRange(value(0, 0, 0.9), value(31, 252, 1.1))
        }
        return BattleCombatStatRangesView(range("hp"), range("atk"), range("def"), range("spa"), range("spd"), range("spe"),
            BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE)
    }

    private fun types(species: jbro.cobblemon.mcc.betterai.engine.dex.Species) = species.types.map { it.lowercase() }.toSet()

    /** The six a trainer sees before it picks its team, with the public facts the server gives the Better AI. */
    protected fun previewOf(entry: List<RefSet>) = BattleOpponentTeamPreviewView(3, entry.mapIndexed { slot, set ->
        val species = dex.species(set.species)!!
        BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:${Js.toID(set.species)}", null, set.level, types(species),
            publicStats(species, set.level))
    })

    /** The Tower trainer of [tier]'s reading of [entry], or null for a tier that does not read the preview. */
    protected fun previewScorer(tier: BattleTrainerTier, entry: List<RefSet>): TeamPreviewSelection.Scorer? =
        TeamPreviewSelection.scorer(tier, previewOf(entry),
            { speciesId, _, level ->
                dex.species(speciesId.substringAfter(':'))?.let { TeamPreviewSelection.Facts(types(it), publicStats(it, level)) }
            },
            { moveId ->
                dex.move(moveId)?.let { move ->
                    val category = when (move.category) {
                        "Physical" -> BattleMoveDamageCategory.PHYSICAL
                        "Special" -> BattleMoveDamageCategory.SPECIAL
                        else -> BattleMoveDamageCategory.STATUS
                    }
                    BattleMoveCandidateView(move.type.lowercase(), category, move.basePower.toDouble(), 1.0, move.priority, 10)
                }
            })

    private fun RefSet.candidate() = TeamPreviewSelection.Candidate("cobblemon:${Js.toID(species)}", null, level, moves)

    /** [values] in the order a trainer reading the preview considers them, as the Tower's selector draws them. */
    private fun <T> considered(random: Random, values: List<T>, scorer: TeamPreviewSelection.Scorer?, score: (T) -> Double?): List<T> {
        scorer ?: return values.shuffled(random)
        return values.map { value ->
            val uniform = ((random.nextLong() ushr 11) + 0.5) / (1L shl 53).toDouble()
            value to (score(value) ?: 0.0) / scorer.temperature - ln(-ln(uniform))
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** A regular trainer's team from [pool], picked from the challenger's preview when [scorer] reads it. */
    protected fun previewTeam(random: Random, pool: List<JsonObject>, scorer: TeamPreviewSelection.Scorer?): List<RefSet> {
        scorer ?: return randomTeam(random, pool, trained = false)
        val team = LinkedHashMap<String, RefSet>()
        for (ref in considered(random, pool.mapNotNull { refSet(it, trained = false) }, scorer) { scorer.score(it.candidate()) }) {
            if (team.size == 3) break
            val base = dex.species(ref.species)!!.baseSpecies
            if (team.keys.none { it == base } && team.values.none { it.item == ref.item }) team[base] = ref
        }
        return team.values.toList()
    }

    /**
     * A Champion's Tera team the way the Tower draws one against a challenger without legendaries: the ace and two
     * other members, each one of its sets; with a [scorer] the others are those that answer the preview best.
     */
    protected fun championTeam(random: Random, scorer: TeamPreviewSelection.Scorer? = null, champion: String? = null): List<RefSet> {
        val name = champion ?: championAces.keys.toList()[random.nextInt(championAces.size)]
        val sets = towerSets.filter {
            it["set_id"].asString.startsWith("champion_${name}_tera_") && LegendaryClassPolicy.categoryFor(it["species_id"].asString) == null
        }.groupBy { it["species_id"].asString }
        val ace = championAces.getValue(name)
        val order = listOf(ace) + considered(random, (sets.keys - ace).toList(), scorer) { species ->
            sets.getValue(species).mapNotNull { refSet(it, trained = false)?.candidate() }.mapNotNull { scorer?.score(it) }.maxOrNull()
        }
        val team = ArrayList<RefSet>()
        for (species in order) {
            if (team.size == 3) break
            val variants = sets.getValue(species)
            val ref = refSet(variants[random.nextInt(variants.size)], trained = false) ?: continue
            if (team.none { it.item == ref.item }) team += ref
        }
        return team
    }

    protected fun fullyTrained(species: String, moves: List<String>, ability: String, item: String, nature: String, attack: String) =
        RefSet(dex.species(species)!!.name, moves, ability = ability, item = item, nature = nature,
            evs = mapOf(attack to 252, "spe" to 252, "hp" to 4), gender = dex.species(species)!!.gender.ifEmpty { "M" })

    /** Strong, ordinary picks a player brings to break a facility: no legendary class. */
    protected val firepower = listOf(
        fullyTrained("dragonite", listOf("extremespeed", "outrage", "earthquake", "firepunch"), "multiscale", "choiceband", "Adamant", "atk"),
        fullyTrained("kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "supremeoverlord", "lifeorb", "Adamant", "atk"),
        fullyTrained("gholdengo", listOf("makeitrain", "shadowball", "focusblast", "nastyplot"), "goodasgold", "choicespecs", "Modest", "spa"),
    )

    /** The same with restricted legendaries, for the Tower's legendary class rule. */
    protected val legendaryFirepower = listOf(
        fullyTrained("calyrexshadow", listOf("astralbarrage", "psyshock", "nastyplot", "protect"), "asonespectrier", "lifeorb", "Timid", "spa"),
        fullyTrained("koraidon", listOf("collisioncourse", "flareblitz", "outrage", "uturn"), "orichalcumpulse", "choiceband", "Jolly", "atk"),
        fullyTrained("miraidon", listOf("electrodrift", "dracometeor", "voltswitch", "dazzlinggleam"), "hadronengine", "choicespecs", "Timid", "spa"),
    )

    protected fun legalMoves(battle: Battle, side: Side): List<Int> {
        val data = side.activeRequest?.active?.firstOrNull() ?: return emptyList()
        return data.moves.withIndex().filter { !Js.truthy(it.value.disabled) }.map { it.index + 1 }
    }

    protected fun benchSwitch(side: Side): String? =
        (side.active.size until side.pokemon.size).firstOrNull { !side.pokemon[it].fainted }?.let { "switch ${it + 1}" }

    protected fun randomChoice(random: Random, battle: Battle, side: Side): String {
        val request = side.activeRequest ?: return ""
        if (request.wait) return ""
        if (request.forceSwitch != null) return benchSwitch(side) ?: "pass"
        return legalMoves(battle, side).takeIf { it.isNotEmpty() }?.let { "move ${it[random.nextInt(it.size)]}" } ?: "move 1"
    }

    /** The move that dealt the most damage over two forked tries against random replies. */
    protected fun greedyChoice(random: Random, battle: Battle, side: Side): String {
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
    protected fun play(player: List<RefSet>, opponent: List<RefSet>, random: Random): Boolean {
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

}

@EnabledIfSystemProperty(named = "betterai.sweeps", matches = "true")
class TowerBalanceScenarioTest : TowerScenarioBase() {
    @Test
    fun `how far a strong team runs through the Tower's regular opponents`() {
        val battles = 200
        val report = StringBuilder("# Battle Tower balance scenario\n\nSingle 3 vs 3, $battles battles per cell, Tera sets, " +
            "both sides on the same greedy policy (so the Tower's AI levels are not modelled). The challenger stays at " +
            "level 50; opponents are one level higher every 5 wins (50 for wins 1 to 5, 51 for 6 to 10, 59 at the " +
            "49th). Regular opponents by stage: tier 1 (IV 15, 0 EVs) wins 1 to 5, tier 2 (IV 20, 252 EVs) 6 to 10, " +
            "tier 3 (IV 25, 384 EVs) 11 to 20, tier 4 (IV 31, 508 EVs) from 21. Every 5th win is a fully trained " +
            "Champion. Each win's opponent is measured at its own level.\n\n")
        fun levelOf(win: Int) = TOWER_LEVEL + (win - 1) / 5
        fun tierOf(win: Int) = when { win <= 5 -> 1; win <= 10 -> 2; win <= 20 -> 3; else -> 4 }
        /** The opponent of a win: "champion" or the regular tier, at the win's level. */
        fun cellOf(win: Int) = (if (win % 5 == 0) 0 else tierOf(win)) to levelOf(win)
        val cells = (1..49).map(::cellOf).distinct()
        fun opponent(cell: Pair<Int, Int>): (Random) -> List<RefSet> = { r ->
            val team = if (cell.first == 0) championTeam(r) else randomTeam(r, pool(false, cell.first), trained = false)
            team.map { it.copy(level = cell.second) }
        }
        fun label(cell: Pair<Int, Int>) = (if (cell.first == 0) "Champion" else "tier ${cell.first}") + " Lv${cell.second}"
        val players: List<Pair<String, (Random) -> List<RefSet>>> = listOf(
            "random species, tier 2 sets" to { r -> randomTeam(r, pool(false, 2), trained = false) },
            "random species, fully trained" to { r -> randomTeam(r, pool(false, 2), trained = true) },
            "Dragonite, Kingambit, Gholdengo" to { _ -> firepower },
        )
        val reachAt = listOf(5, 10, 15, 20, 25, 30, 40, 49)
        report.append("| player team | " + cells.joinToString(" | ", transform = ::label) + " | " +
            reachAt.joinToString(" | ") { "reach $it" } + " |\n")
        report.append("|---|" + cells.joinToString("") { "---|" } + reachAt.joinToString("") { "---|" } + "\n")
        players.forEachIndexed { playerIndex, (playerName, player) ->
            val rates = cells.mapIndexed { cellIndex, cell ->
                val random = Random(20261002L + playerIndex * 31L + cellIndex)
                var wins = 0
                repeat(battles) { if (play(player(random), opponent(cell)(random), random)) wins++ }
                cell to wins.toDouble() / battles
            }.toMap()
            fun reach(wins: Int) = (1..wins).fold(1.0) { p, win -> p * rates.getValue(cellOf(win)) }
            report.append("| $playerName | " + cells.joinToString(" | ") { "%.1f%%".format(rates.getValue(it) * 100) } + " | " +
                reachAt.joinToString(" | ") { "%.1f%%".format(reach(it) * 100) } + " |\n")
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

/**
 * The strong team's win rate over the opponents' training and level, for choosing the Tower's curve: regular sets
 * (tier 1 for the first wins, the battle sets after) and Champions, each at training grades 0 to 3 and several levels.
 * Report: build/reports/tower-grid.csv (kind, grade, level, win rate).
 *
 *     ./gradlew :more-cobblemon-contents:unitTest -Pscope=engine -Ptests=TowerBalanceGrid -Psweeps
 */
@EnabledIfSystemProperty(named = "betterai.sweeps", matches = "true")
class TowerBalanceGridScenarioTest : TowerScenarioBase() {
    /**
     * [set] at training [grade]: 0 is IV 15 and no EVs, 1 IV 20 and 252 in its main stat, 2 IV 25 and 252/128/4,
     * 3 IV 31 and its full 252/252/4 spread.
     */
    private fun graded(set: RefSet, grade: Int): RefSet {
        val full = set.evs.filterValues { it == 252 }.keys
        val main = listOf("atk", "spa", "hp").firstOrNull { it in full } ?: full.first()
        val second = (full - main).firstOrNull()
        val rest = set.evs.entries.firstOrNull { it.value == 4 }?.key
        val evs = when (grade) {
            0 -> emptyMap()
            1 -> mapOf(main to 252)
            2 -> listOfNotNull(main to 252, second?.let { it to 128 }, rest?.let { it to 4 }).toMap()
            else -> set.evs
        }
        return set.copy(evs = evs, ivs = RefSet.STATS.associateWith { listOf(15, 20, 25, 31)[grade] })
    }

    @Test
    fun `win rates over the opponents' training and level`() {
        val battles = 200
        val cells = ArrayList<Triple<String, Pair<Int, Int>, (Random) -> List<RefSet>>>()
        for (grade in 0..3) {
            for (level in listOf(50, 52)) {
                cells += Triple("tier1", grade to level) { r: Random -> randomTeam(r, pool(false, 1), trained = false).map { graded(it, grade).copy(level = level) } }
            }
            for (level in listOf(50, 52, 54, 56, 58, 60)) {
                cells += Triple("regular", grade to level) { r: Random -> randomTeam(r, pool(false, 4), trained = false).map { graded(it, grade).copy(level = level) } }
            }
            for (level in listOf(50, 52, 54, 56, 58)) {
                cells += Triple("champion", grade to level) { r: Random -> championTeam(r).map { graded(it, grade).copy(level = level) } }
            }
        }
        val csv = StringBuilder("kind,grade,level,rate\n")
        cells.forEachIndexed { index, (kind, cell, opponent) ->
            val random = Random(20261004L + index)
            var wins = 0
            repeat(battles) { if (play(firepower, opponent(random), random)) wins++ }
            csv.append("$kind,${cell.first},${cell.second},${wins.toDouble() / battles}\n")
        }
        val out = Path.of("build/reports/tower-grid.csv")
        Files.createDirectories(out.parent)
        Files.writeString(out, csv)
        println(csv)
    }
}
