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

        /** Never throws: a failed write is logged and the decision proceeds unchanged. */
        fun write(snapshot: AiTestDecisionSnapshot): Path? = try {
            val root = directory() ?: return null
            val battleDirectory = root.resolve(snapshot.battleId.toString())
            Files.createDirectories(battleDirectory)
            val name = "turn-%03d-%s.json".format(snapshot.turn, snapshot.context.requestId.toString().take(8))
            battleDirectory.resolve(name).also { Files.writeString(it, toJson(snapshot)) }
        } catch (failure: Exception) {
            logger.warn("Better AI decision snapshot was not saved: {}", failure.javaClass.simpleName)
            null
        }

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
