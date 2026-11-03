package jbro.cobblemon.policy.support

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What Pichu says and proposes: the AI's guidance, the commands it may put up for approval and its wording. Lives in
 * `config/jbro-policy-pichu.json` and is read at each use, so an edit applies without a restart. Keys the file leaves
 * out take the bundled default (`/pichu-rules.json`); a broken file keeps the last good one.
 */
internal class PichuRules private constructor(private val root: JsonObject) {
    /** The text at a dotted [path], with each `{name}` filled once; a value's own braces are left alone. */
    fun text(path: String, vararg values: Pair<String, Any?>): String = fill(string(path), values.toMap())

    /** The lines at [path] as one text, filled like [text]. */
    fun lines(path: String, vararg values: Pair<String, Any?>): String =
        fill(list(path).joinToString("\n"), values.toMap())

    fun list(path: String): List<String> =
        (element(path)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()).map { it.asString }

    fun flag(path: String): Boolean = element(path)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    /** Each entry's `minShare` and `tone`, lowest share first. */
    fun tones(path: String): List<Pair<Double, String>> =
        (element(path)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList())
            .map { it.asJsonObject.let { tier -> tier.get("minShare").asDouble to tier.get("tone").asString } }
            .sortedBy { it.first }

    /** The pattern at [path]; one that does not compile gives the bundled default's. */
    fun regex(path: String): Regex {
        val pattern = string(path)
        return compiled.getOrPut(pattern) {
            try { Regex(pattern) } catch (failure: Exception) {
                warn("Invalid pattern at $path in the Pichu rules; using the default", failure)
                Regex(defaults.string(path))
            }
        }
    }

    private fun string(path: String) = element(path)?.takeIf { it.isJsonPrimitive }?.asString ?: path

    private fun element(path: String): JsonElement? =
        path.split('.').fold(root as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

    private fun fill(template: String, values: Map<String, Any?>) = PLACEHOLDER.replace(template) { match ->
        if (match.groupValues[1] in values) values[match.groupValues[1]]?.toString().orEmpty() else match.value
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{(\\w+)}")
        private val compiled = java.util.concurrent.ConcurrentHashMap<String, Regex>()
        private val defaultText by lazy {
            requireNotNull(PichuRules::class.java.getResourceAsStream("/pichu-rules.json")).use {
                String(it.readAllBytes(), StandardCharsets.UTF_8)
            }
        }
        private val defaults by lazy { PichuRules(JsonParser.parseString(defaultText).asJsonObject) }

        private var file: Path? = null
        private var warn: (String, Throwable) -> Unit = { _, _ -> }
        private var lastText: String? = null
        private var last: PichuRules? = null

        /** Points the rules at [path], writing the defaults there when it is missing. */
        @Synchronized
        fun use(path: Path, warn: (String, Throwable) -> Unit) {
            file = path
            this.warn = warn
            lastText = null
            last = null
            try {
                Files.createDirectories(path.parent)
                Files.writeString(path, defaultText, StandardOpenOption.CREATE_NEW)
            } catch (_: FileAlreadyExistsException) {
                // Keep the operator's edits.
            } catch (failure: Exception) {
                warn("Could not create Pichu rules at $path", failure)
            }
        }

        /** The rules as the file stands now. */
        @Synchronized
        fun current(): PichuRules {
            val path = file ?: return defaults
            val text = try {
                Files.readString(path)
            } catch (failure: Exception) {
                warn("Could not read Pichu rules at $path; keeping the last good ones", failure)
                return last ?: defaults
            }
            if (text != lastText) {
                lastText = text
                try {
                    val edited = JsonParser.parseString(text).asJsonObject
                    last = PichuRules(merged(JsonParser.parseString(defaultText).asJsonObject, edited))
                } catch (failure: Exception) {
                    warn("Invalid Pichu rules at $path; keeping the last good ones", failure)
                }
            }
            return last ?: defaults
        }

        /** [base] with [edited] over it, object by object; a list in [edited] replaces the whole list. */
        private fun merged(base: JsonObject, edited: JsonObject): JsonObject = base.apply {
            for ((key, value) in edited.entrySet()) {
                val old = get(key)
                add(key, if (old is JsonObject && value is JsonObject) merged(old, value) else value)
            }
        }
    }
}
