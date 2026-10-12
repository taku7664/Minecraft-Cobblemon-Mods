package jbro.cobblemon.npc.dialogue

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads and writes the dialogue files:
 *
 * ```json
 * { "speaker": "Guide", "skin": "Steve", "start": "hello",
 *   "nodes": { "hello": { "lines": ["Hi {player}!"], "choices": [{ "text": "Bye", "next": null }],
 *              "branches": [{ "if": "tag:vip", "next": "vip" }], "next": "...", "commands": ["/..."] } } }
 * ```
 */
object DialogueCodec {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** The dialogue as read, null when the JSON has no dialogue shape at all, and what is wrong with it. */
    data class Decoded(val dialogue: NpcDialogue?, val problems: List<DialogueProblem>) {
        /** The dialogue when it holds together, ready to play. */
        val valid: NpcDialogue? get() = dialogue?.takeIf { problems.isEmpty() }
    }

    /** Reads [json]; check [Decoded.valid] before playing the result. */
    fun decode(json: String): Decoded {
        val root = try {
            JsonParser.parseString(json)
        } catch (error: Exception) {
            return Decoded(null, listOf(DialogueProblem("json", listOf(error.message.orEmpty().take(200)))))
        }
        if (root !is JsonObject) return Decoded(null, listOf(DialogueProblem("json", listOf("not an object"))))
        val problems = mutableListOf<DialogueProblem>()
        val nodes = linkedMapOf<String, DialogueNode>()
        (root.get("nodes") as? JsonObject)?.entrySet()?.forEach { (id, value) ->
            val node = value as? JsonObject
            if (node == null) problems += DialogueProblem("node_shape", listOf(id)) else nodes[id] = readNode(node)
        }
        val dialogue = NpcDialogue(
            start = root.string("start").orEmpty(),
            nodes = nodes,
            speaker = root.string("speaker")?.takeIf(String::isNotBlank),
            skin = root.string("skin")?.takeIf(String::isNotBlank),
        )
        problems += validate(dialogue)
        return Decoded(dialogue, problems)
    }

    /** What is wrong with [dialogue]: unknown nodes, unreadable conditions or commands. */
    fun validate(dialogue: NpcDialogue): List<DialogueProblem> {
        return validateLocated(dialogue).map { it.problem }
    }

    fun validateLocated(dialogue: NpcDialogue): List<DialogueIssue> {
        val issues = mutableListOf<DialogueIssue>()
        fun add(node: String?, part: DialogueIssue.Part, index: Int = -1, problem: DialogueProblem) {
            issues += DialogueIssue(node, part, index, problem)
        }
        if (dialogue.nodes.isEmpty()) add(null, DialogueIssue.Part.NODE, problem = DialogueProblem("no_nodes"))
        if (dialogue.start !in dialogue.nodes) add(null, DialogueIssue.Part.START, problem = DialogueProblem("start", listOf(dialogue.start)))
        dialogue.nodes.forEach { (id, node) ->
            if (!DialogueIds.isNodeId(id)) add(id, DialogueIssue.Part.NODE, problem = DialogueProblem("node_id", listOf(id)))
            if (node.lines.size > DialogueNode.MAX_ITEMS) add(id, DialogueIssue.Part.NEXT,
                problem = DialogueProblem("page_limit", listOf(id, DialogueNode.MAX_ITEMS.toString())))
            if (node.choices.size > DialogueNode.MAX_ITEMS) add(id, DialogueIssue.Part.CHOICE, 0,
                DialogueProblem("choice_limit", listOf(id, DialogueNode.MAX_ITEMS.toString())))
            fun target(next: String?, part: DialogueIssue.Part, index: Int = -1) {
                if (next != null && next !in dialogue.nodes) add(id, part, index, DialogueProblem("next", listOf(id, next)))
            }
            fun condition(text: String, part: DialogueIssue.Part, index: Int) {
                val problems = mutableListOf<DialogueProblem>()
                DialogueCondition.parse(text, problems)
                problems.forEach { add(id, part, index, it) }
            }
            target(node.next, DialogueIssue.Part.NEXT)
            node.choices.forEachIndexed { index, choice ->
                if (choice.text.isBlank()) add(id, DialogueIssue.Part.CHOICE, index, DialogueProblem("choice_text", listOf(id)))
                target(choice.next, DialogueIssue.Part.CHOICE, index)
                choice.condition?.let { condition(it, DialogueIssue.Part.CHOICE, index) }
            }
            node.branches.forEachIndexed { index, branch ->
                target(branch.next, DialogueIssue.Part.BRANCH, index)
                condition(branch.condition, DialogueIssue.Part.BRANCH, index)
            }
            if (node.choices.isNotEmpty() && node.lines.isEmpty()) add(id, DialogueIssue.Part.NEXT, problem = DialogueProblem("choices_without_lines", listOf(id)))
            node.commands.forEachIndexed { index, command ->
                if (DialogueCommand.parse(command) == null) add(id, DialogueIssue.Part.COMMAND, index, DialogueProblem("command", listOf(id, command)))
            }
        }
        return issues
    }

    fun encode(dialogue: NpcDialogue): String {
        val root = JsonObject()
        dialogue.speaker?.let { root.addProperty("speaker", it) }
        dialogue.skin?.let { root.addProperty("skin", it) }
        root.addProperty("start", dialogue.start)
        root.add("nodes", JsonObject().also { nodes ->
            dialogue.nodes.forEach { (id, node) -> nodes.add(id, writeNode(node)) }
        })
        return gson.toJson(root)
    }

    private fun readNode(json: JsonObject) = DialogueNode(
        lines = json.strings("lines"),
        choices = json.objects("choices").map {
            DialogueChoice(it.string("text").orEmpty(), it.string("next")?.takeIf(String::isNotBlank), it.string("if")?.takeIf(String::isNotBlank))
        },
        branches = json.objects("branches").map { DialogueBranch(it.string("if").orEmpty(), it.string("next").orEmpty()) },
        next = json.string("next")?.takeIf(String::isNotBlank),
        commands = json.strings("commands"),
    )

    private fun writeNode(node: DialogueNode) = JsonObject().apply {
        if (node.lines.isNotEmpty()) add("lines", node.lines.toJsonArray())
        if (node.choices.isNotEmpty()) add("choices", JsonArray().also { array ->
            node.choices.forEach { choice ->
                array.add(JsonObject().apply {
                    addProperty("text", choice.text)
                    addProperty("next", choice.next)
                    choice.condition?.let { addProperty("if", it) }
                })
            }
        })
        if (node.branches.isNotEmpty()) add("branches", JsonArray().also { array ->
            node.branches.forEach { branch ->
                array.add(JsonObject().apply { addProperty("if", branch.condition); addProperty("next", branch.next) })
            }
        })
        node.next?.let { addProperty("next", it) }
        if (node.commands.isNotEmpty()) add("commands", node.commands.toJsonArray())
    }

    private fun List<String>.toJsonArray() = JsonArray().also { array -> forEach(array::add) }

    private fun JsonObject.string(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.strings(key: String): List<String> = when (val value = get(key)) {
        is JsonArray -> value.filterNot(JsonElement::isJsonNull).map(JsonElement::getAsString)
        null -> emptyList()
        else -> if (value.isJsonNull) emptyList() else listOf(value.asString)
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
}
