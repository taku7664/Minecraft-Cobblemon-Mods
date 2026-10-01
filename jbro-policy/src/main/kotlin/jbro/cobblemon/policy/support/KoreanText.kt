package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.Optional
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.contents.TranslatableContents

/**
 * Turns a [Component] into Korean plain text for Discord. A dedicated server only knows English, so this reads every
 * mod's own `ko_kr.json` and fills translatable components the way a Korean client would; keys no mod translates
 * (vanilla Minecraft's) fall back to the server's English.
 */
internal object KoreanText {
    private val table: Map<String, String> by lazy(::loadModTranslations)
    private val ARGUMENT = Regex("%(?:(\\d+)\\$)?([sd%])")

    fun render(component: Component): String = buildString { append(component, this) }

    /** The Korean text of [key], or null when no mod translates it. */
    fun translate(key: String): String? = table[key]

    /** Every key and its Korean text, for reverse lookups such as a Pokémon by its Korean name. */
    fun entries(): Map<String, String> = table

    internal fun format(pattern: String, args: Array<out Any?>): String {
        var next = 0
        return ARGUMENT.replace(pattern) { match ->
            if (match.groupValues[2] == "%") return@replace "%"
            val index = match.groupValues[1].takeIf { it.isNotEmpty() }?.let { it.toInt() - 1 } ?: next++
            when (val arg = args.getOrNull(index)) {
                null -> ""
                is Component -> render(arg)
                else -> arg.toString()
            }
        }
    }

    private fun append(component: Component, out: StringBuilder) {
        when (val contents = component.contents) {
            is TranslatableContents -> {
                val pattern = table[contents.key]
                    ?: Language.getInstance().takeIf { it.has(contents.key) }?.getOrDefault(contents.key)
                    ?: contents.fallback
                    ?: contents.key
                out.append(format(pattern, contents.args))
            }
            else -> contents.visit(FormattedText.ContentConsumer<Unit> { text -> out.append(text); Optional.empty() })
        }
        component.siblings.forEach { append(it, out) }
    }

    private fun loadModTranslations(): Map<String, String> {
        val result = HashMap<String, String>()
        for (mod in FabricLoader.getInstance().allMods) {
            for (root in mod.rootPaths) {
                val assets = root.resolve("assets")
                if (!Files.isDirectory(assets)) continue
                try {
                    Files.list(assets).use { namespaces ->
                        namespaces.forEach { namespace ->
                            val file = namespace.resolve("lang").resolve("ko_kr.json")
                            if (Files.isRegularFile(file)) {
                                JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (key, value) ->
                                    if (value.isJsonPrimitive) result.putIfAbsent(key, value.asString)
                                }
                            }
                        }
                    }
                } catch (failure: Exception) {
                    // A mod's own broken file (Mega Showdown ships one); the client skips it too, so one line is enough.
                    JbroPolicy.LOGGER.warn("Could not read Korean translations of {}: {}", mod.metadata.id, failure.message)
                }
            }
        }
        JbroPolicy.LOGGER.info("Loaded {} Korean translations for Discord", result.size)
        return result
    }
}
