package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Self-play arena for `works/tools/betterai-arena`: PAIRS team pairs from the preset sets (SEED picks them), each played
 * boss against boss as in the game (searchless, quick decider on, the opposing team preview and Boss's exact spreads
 * handed over), THREADS battles at once. Every battle keeps its folder (decisions.jsonl, result.json) under OUT; one
 * `ARENA {...}` line per battle goes to stdout for the arena's server.
 *
 * Run with java alone: `java -cp <test runtime classpath> jbro.cobblemon.mcc.betterai.BetterAiArenaKt OUT PAIRS SEED THREADS SHOWDOWN`.
 */
fun main(args: Array<String>) {
    val out = Path.of(args[0])
    val pairs = args.getOrNull(1)?.toInt() ?: 5
    val seed = args.getOrNull(2)?.toInt() ?: 1
    val threads = (args.getOrNull(3)?.toInt() ?: 3).coerceAtLeast(1)
    val game = Path.of(args.getOrNull(4) ?: error("the dev server's showdown folder is required"))
    MegaShowdownTestSpecies.install()
    val workers = ThreadLocal.withInitial { jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker() }
    jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = object : jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease {
        override fun <T> withWorker(deadlineNanos: Long, action: (jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker) -> T): T? = action(workers.get())
    }
    val root = out.resolve("engine")
    Files.createDirectories(root)
    val audit = EmbeddedPresetAudit.run(root.resolve("audit"), teamPairs = pairs, teamSeed = seed)
    val engine = root.resolve("audit/engine")
    for (file in listOf("side.js", "pokemon.js", "battle.js", "battle-actions.js")) {
        Files.copy(game.resolve("sim/$file"), engine.resolve("sim/$file"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    val persona = jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds.AI_TEST_PERSONA_PREFIX + "arena"
    // As the game hands every trainer the opposing preview: without it the opponent stat assumptions never apply.
    val teamPairs = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")
        .map { it.asJsonObject.deepCopy().apply { addProperty("teamPreview", true) } }
    val pool = Executors.newFixedThreadPool(threads)
    val started = System.nanoTime()
    println("ARENA {\"event\":\"start\",\"battles\":${teamPairs.size}}")
    teamPairs.forEachIndexed { index, pair ->
        pool.submit {
            val battle = out.resolve("battle-$index")
            Files.createDirectories(battle)
            val result = runCatching {
                EmbeddedTeamBattle.run(engine, pair, battle, 200, trainerProfile = BattleTrainerProfile.boss(),
                    searchlessSides = mapOf("p1" to true, "p2" to true),
                    trainerPersonaIds = mapOf("p1" to persona, "p2" to persona))
            }
            val line = result.fold(
                { "{\"event\":\"battle\",\"index\":$index,\"status\":${it["status"]},\"turn\":${it["turn"]},\"winner\":${it["winner"]}}" },
                { "{\"event\":\"battle\",\"index\":$index,\"failed\":${com.google.gson.JsonPrimitive(it.toString())}}" })
            println("ARENA $line")
        }
    }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.DAYS)
    println("ARENA {\"event\":\"done\",\"seconds\":${(System.nanoTime() - started) / 1_000_000_000}}")
    // The engine workers and Showdown processes keep threads alive; the arena is done.
    kotlin.system.exitProcess(0)
}
