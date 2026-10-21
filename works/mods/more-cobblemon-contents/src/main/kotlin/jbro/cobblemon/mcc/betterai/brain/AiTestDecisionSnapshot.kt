package jbro.cobblemon.mcc.betterai.brain

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleStrategyBrief
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import org.slf4j.LoggerFactory

/**
 * One AI-test decision input, saved so a live position can be replayed as a unit test.
 *
 * `context` is the raw request the Brain received, before stat assumptions or public calculation,
 * so a replay runs the same pipeline. Native continuation state is not captured; a replay of a
 * turn that continued native search runs from the legacy or opening path instead.
 */
internal data class AiTestDecisionSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val battleId: UUID,
    val turn: Int,
    val trainerPersonaId: String?,
    val trainerProfile: BattleTrainerProfile,
    val strategy: BattleStrategyBrief?,
    val nativeContinuation: Boolean,
    /** Wall clock at capture; a replay keeps the same remaining time before the request deadline. */
    val capturedAtEpochMillis: Long,
    val context: BattleDecisionContext,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val DIRECTORY_PROPERTY = "betterai.decisionSnapshotDir"

        private val logger = LoggerFactory.getLogger("more_cobblemon_contents/decision_snapshot")

        val gson: Gson = GsonBuilder()
            .registerTypeAdapter(UUID::class.java, UuidAdapter().nullSafe())
            .serializeSpecialFloatingPointValues()
            .serializeNulls()
            .create()

        fun toJson(snapshot: AiTestDecisionSnapshot): String = gson.toJson(snapshot)

        fun fromJson(json: String): AiTestDecisionSnapshot {
            val version = gson.fromJson(json, JsonObject::class.java)["schemaVersion"]?.asInt
            require(version == SCHEMA_VERSION) { "Unsupported decision snapshot schema $version" }
            return gson.fromJson(json, AiTestDecisionSnapshot::class.java)
        }

        /**
         * Whether every battle's decisions are kept, not only the AI test trainers': `recordDecisions` in the Better AI
         * config (`config/more-cobblemon-contents/openrouter.json`), set by the mod initializer. A league decision
         * nobody could open afterwards could not be explained (a Drapion's Swords Dance, a Dubwool's Rest, 2026-10-20).
         */
        @Volatile
        var recordsEveryBattle: Boolean = false

        /**
         * Never throws: a failed write is logged and the decision proceeds unchanged. [compressed] writes `.json.gz` (a
         * decision is some 300 KB of JSON, a tenth of it compressed), and a new battle's folder prunes the oldest beyond
         * [KEPT_BATTLES].
         */
        fun write(snapshot: AiTestDecisionSnapshot, compressed: Boolean = false): Path? = try {
            val root = directory() ?: return null
            val battleDirectory = root.resolve(snapshot.battleId.toString())
            if (!Files.isDirectory(battleDirectory)) {
                Files.createDirectories(battleDirectory)
                if (compressed) prune(root, battleDirectory)
            }
            val name = "turn-%03d-%s.json".format(snapshot.turn, snapshot.context.requestId.toString().take(8))
            if (compressed) battleDirectory.resolve("$name.gz").also { path ->
                java.util.zip.GZIPOutputStream(Files.newOutputStream(path)).use { it.write(toJson(snapshot).toByteArray(Charsets.UTF_8)) }
            } else battleDirectory.resolve(name).also { Files.writeString(it, toJson(snapshot)) }
        } catch (failure: Exception) {
            logger.warn("Better AI decision snapshot was not saved: {}", failure.javaClass.simpleName)
            null
        }

        /**
         * The decision's stages ([LocalDecisionStages], inner values included) beside its snapshot, as
         * `turn-NNN-<request>.stages.json(.gz)`. Never throws, as [write].
         */
        fun writeStages(battleId: UUID, turn: Int, requestId: UUID, stages: LocalDecisionStages, compressed: Boolean = false): Path? = try {
            val root = directory()
            if (root == null) null else {
                val battleDirectory = root.resolve(battleId.toString())
                Files.createDirectories(battleDirectory)
                val name = "turn-%03d-%s.stages.json".format(turn, requestId.toString().take(8))
                val json = gson.toJson(stages)
                if (compressed) battleDirectory.resolve("$name.gz").also { path ->
                    java.util.zip.GZIPOutputStream(Files.newOutputStream(path)).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                } else battleDirectory.resolve(name).also { Files.writeString(it, json) }
            }
        } catch (failure: Exception) {
            logger.warn("Better AI decision stages were not saved: {}", failure.javaClass.simpleName)
            null
        }

        /** The battle folders beyond the newest [KEPT_BATTLES], [keep] aside, are deleted. */
        private fun prune(root: Path, keep: Path) {
            val battles = Files.list(root).use { stream -> stream.filter { Files.isDirectory(it) && it != keep }.toList() }
                .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
            battles.drop(KEPT_BATTLES - 1).forEach { old ->
                Files.walk(old).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

        private const val KEPT_BATTLES = 50

        /** Set once by the mod initializer; unit tests leave it unset, so they never write files. */
        @Volatile
        var configuredDirectory: Path? = null

        private fun directory(): Path? =
            System.getProperty(DIRECTORY_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: configuredDirectory
    }

    private class UuidAdapter : TypeAdapter<UUID>() {
        override fun write(out: JsonWriter, value: UUID) {
            out.value(value.toString())
        }

        override fun read(input: JsonReader): UUID = UUID.fromString(input.nextString())
    }
}
