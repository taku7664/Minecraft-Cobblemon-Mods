package jbro.cobblemon.npc.server

import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.dialogue.DialogueCodec
import jbro.cobblemon.npc.dialogue.DialogueIds
import jbro.cobblemon.npc.dialogue.DialogueNode
import jbro.cobblemon.npc.dialogue.DialogueProblem
import jbro.cobblemon.npc.dialogue.NpcDialogue
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The dialogues in `config/cobblemon_npc/dialogues/<id>.json`, read at server start and on `/npc reload`. */
object DialogueStore {
    private val directory: Path get() = FabricLoader.getInstance().configDir.resolve(CobblemonNpc.MOD_ID).resolve("dialogues")
    private val examples = listOf("tower_guide")
    private var dialogues: Map<String, NpcDialogue> = emptyMap()

    fun ids(): List<String> = dialogues.keys.sorted()

    operator fun get(id: String): NpcDialogue? = dialogues[id]

    /** Reads every file again; returns the files that failed with their problems. */
    fun load(): Map<String, List<DialogueProblem>> {
        val directory = directory
        if (Files.notExists(directory)) {
            Files.createDirectories(directory)
            writeExamples(directory)
        }
        val loaded = sortedMapOf<String, NpcDialogue>()
        val failed = sortedMapOf<String, List<DialogueProblem>>()
        directory.listDirectoryEntries("*.json").forEach { file ->
            val id = file.nameWithoutExtension
            if (!DialogueIds.isDialogueId(id)) {
                failed[id] = listOf(DialogueProblem("dialogue_id", listOf(id)))
                return@forEach
            }
            val decoded = DialogueCodec.decode(file.readText())
            decoded.valid?.let { loaded[id] = it } ?: run { failed[id] = decoded.problems }
        }
        failed.forEach { (id, problems) -> CobblemonNpc.LOGGER.warn("Dialogue {} not loaded: {}", id, problems) }
        dialogues = loaded
        return failed
    }

    /** The file text of [id], or a starting template for a new one. */
    fun source(id: String): String {
        val file = directory.resolve("$id.json")
        if (Files.exists(file)) return file.readText()
        return DialogueCodec.encode(NpcDialogue("start", linkedMapOf("start" to DialogueNode(lines = listOf("..."))), speaker = null))
    }

    /** Saves [dialogue] as [id], replacing the file in one move. */
    fun save(id: String, dialogue: NpcDialogue) {
        require(DialogueIds.isDialogueId(id))
        Files.createDirectories(directory)
        val file = directory.resolve("$id.json")
        val staged = directory.resolve("$id.json.saving")
        staged.writeText(DialogueCodec.encode(dialogue))
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        dialogues = dialogues + (id to dialogue)
    }

    private fun writeExamples(directory: Path) {
        examples.forEach { name ->
            val stream = javaClass.getResourceAsStream("/${CobblemonNpc.MOD_ID}/examples/$name.json") ?: return@forEach
            stream.use { Files.copy(it, directory.resolve("$name.json")) }
        }
    }
}
