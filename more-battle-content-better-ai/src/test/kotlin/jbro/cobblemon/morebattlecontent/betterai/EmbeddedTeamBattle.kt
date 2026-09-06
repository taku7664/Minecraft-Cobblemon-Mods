package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object EmbeddedTeamBattle {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected new output directory, pair count and sampling seed" }
        val directory = Path.of(args[0]).toAbsolutePath()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = args[1].toInt(), teamSeed = args[2].toInt())
        Files.writeString(directory.resolve("audit.json"), audit.toString(), CREATE_NEW)
        val pairs = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")
        pairs.forEachIndexed { index, pair ->
            val result = run(directory.resolve("audit/engine"), pair.asJsonObject, directory.resolve("battle-$index"))
            println("NATIVE_TEAM_BATTLE index=$index status=${result["status"]} turn=${result["turn"]} decisions=${result["decisions"]} forced=${result["forcedSwitchDecisions"]} winner=${result["winner"]}")
        }
        println("native team capture: $directory")
    }

    fun run(engine: Path, pair: JsonObject, directory: Path, maxTurns: Int = 200,
        p1Tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        p2Tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT): JsonObject {
        val battleId = UUID.nameUUIDFromBytes(pair["battleSeed"].toString().toByteArray())
        val tunings = mapOf("p1" to p1Tuning, "p2" to p2Tuning)
        val brains = tunings.mapValues { (_, tuning) -> LocalTacticalBrain(tuning = tuning) }
        val sessions = brains.mapValues { (_, brain) -> brain.openSession(BattleBrainOpenContext(battleId, BattleFormat.SINGLE)) }
        var final: JsonObject? = null
        val counts = mutableMapOf("p1" to 0, "p2" to 0)
        var forced = 0
        var effectAnnotatedCandidates = 0
        try {
            NativeSession(engine, pair, directory, maxTurns).use { native ->
                Files.newBufferedWriter(directory.resolve("decisions.jsonl"), CREATE_NEW).use { trace ->
                    repeat(maxTurns * 8 + 20) { round ->
                        val frame = native.read()
                        if (frame["status"].asString != "WAITING") {
                            check(frame["status"].asString in setOf("COMPLETE", "TURN_LIMIT"))
                            native.awaitExit()
                            final = frame
                            frame.addProperty("decisions", counts.values.sum())
                            frame.add("decisionsBySide", JsonObject().apply { counts.forEach { (side, count) -> addProperty(side, count) } })
                            frame.addProperty("forcedSwitchDecisions", forced)
                            frame.addProperty("illegalChoices", 0) // Any illegal/rejected choice aborts instead of producing a result.
                            frame.addProperty("effectAnnotatedCandidates", effectAnnotatedCandidates)
                            frame.add("policyTuning", com.google.gson.Gson().toJsonTree(tunings))
                            frame.addProperty("evidence", "NATIVE_LOCAL_BRAIN_TEAMS_PARTIAL_INPUT_ADAPTER_NOT_QUALITY_PROOF")
                            frame.addProperty("adapterLimits", "PARTIAL_DECLARATIVE_EFFECTS_NO_CALLBACK_EXECUTION;PARTIAL_PUBLIC_EVENTS_AND_VOLATILES;UNKNOWN_EFFECT_DURATIONS;NO_GIMMICK_CANDIDATES;INCOMPLETE_FUTURE_PP;NO_RUNTIME_ADDONS")
                            Files.writeString(directory.resolve("result.json"), frame.toString(), CREATE_NEW)
                            return frame
                        }
                        val choices = JsonObject()
                        frame.getAsJsonArray("requests").forEach { value ->
                            val input = value.asJsonObject
                            val side = input["side"].asString
                            val context = EmbeddedTeamInput.context(input, battleId, frame["turn"].asInt, round)
                            val effects = context.candidates.filter { it.kind == BattleActionKind.USE_MOVE }
                                .associate { it.actionId to it.moveDetails?.effects }
                            effectAnnotatedCandidates += effects.values.count { it != null }
                            val decision = brains.getValue(side).decide(sessions.getValue(side), context)
                                .toCompletableFuture().get(25, TimeUnit.SECONDS)
                            check(decision.requestId == context.requestId && context.candidates.any { it.actionId == decision.actionId })
                            if (input.getAsJsonObject("request").has("forceSwitch")) forced++
                            counts[side] = counts.getValue(side) + 1
                            choices.addProperty(side, decision.actionId)
                            trace.append(JsonObject().apply {
                                addProperty("side", side); addProperty("turn", frame["turn"].asInt)
                                add("input", input); addProperty("actionId", decision.actionId)
                                add("candidateEffects", com.google.gson.Gson().toJsonTree(effects))
                                add("decisionTags", com.google.gson.Gson().toJsonTree(decision.tags))
                            }.toString()).appendLine()
                            trace.flush()
                        }
                        native.send(JsonObject().apply { add("choices", choices) })
                    }
                    error("Native request limit exceeded without terminal result")
                }
            }
        } finally {
            brains.forEach { (side, brain) ->
                val winner = final?.get("winner")?.takeUnless { it.isJsonNull }?.asString
                val outcome = when {
                    final?.get("status")?.asString != "COMPLETE" -> BattleBrainCloseOutcome.CANCELLED
                    winner == null -> BattleBrainCloseOutcome.CANCELLED
                    winner == side -> BattleBrainCloseOutcome.VICTORY
                    else -> BattleBrainCloseOutcome.DEFEAT
                }
                brain.closeSession(sessions.getValue(side), BattleBrainCloseResult(outcome, counts.getValue(side)))
            }
        }
    }

    internal class NativeSession(engine: Path, pair: JsonObject, directory: Path, maxTurns: Int) : AutoCloseable {
        private val readerExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "native-team-output").apply { isDaemon = true } }
        private val stderr: Path
        private val process: Process
        private val reader: java.io.BufferedReader
        private val writer: java.io.BufferedWriter
        init {
            require(maxTurns in 1..10000)
            Files.createDirectories(directory)
            val script = directory.resolve("bridge.cjs").toAbsolutePath()
            val input = directory.resolve("referee-input.json").toAbsolutePath()
            stderr = directory.resolve("stderr.txt").toAbsolutePath()
            Files.write(script, requireNotNull(javaClass.getResourceAsStream("/oracle/team-battle-bridge.cjs")).use { it.readBytes() }, CREATE_NEW)
            Files.writeString(input, pair.deepCopy().apply { addProperty("maxTurns", maxTurns) }.toString(), CREATE_NEW)
            val builder = ProcessBuilder("node", "--unhandled-rejections=strict", script.toString(), engine.toAbsolutePath().toString(), input.toString())
                .redirectError(stderr.toFile())
            builder.environment().remove("NODE_OPTIONS"); builder.environment().remove("NODE_PATH")
            process = builder.start()
            reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        }
        fun read(): JsonObject {
            val line = readerExecutor.submit<String?> { reader.readLine() }.get(10, TimeUnit.SECONDS)
            check(line != null) { "Native bridge ended unexpectedly: ${Files.readString(stderr).take(4000)}" }
            return JsonParser.parseString(line).asJsonObject
        }
        fun send(value: JsonObject) { writer.append(value.toString()).appendLine(); writer.flush() }
        fun awaitExit() {
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Native bridge failed or did not exit: ${Files.readString(stderr).take(4000)}"
            }
        }
        override fun close() {
            if (process.isAlive) process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            readerExecutor.shutdownNow()
            reader.close(); writer.close()
        }
    }
}
