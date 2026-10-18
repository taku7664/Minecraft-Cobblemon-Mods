package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Mega Showdown's species additions (its Z-A Megas and the like) as Showdown species JSON, for the engine dex outside the
 * game ([EngineRuntimeDex.outsideGameSpecies]). In the game Cobblemon registers them and the engine reads its registry; a
 * test has none, and a Z-A Mega was an "Unidentified species" the engine could not play (league run, 2026-10-19).
 * The jar is the one next to the Showdown the tests point at (`aiengine.showdown`/../mods).
 */
internal object MegaShowdownTestSpecies {
    fun install() {
        if (EngineRuntimeDex.outsideGameSpecies == null) EngineRuntimeDex.outsideGameSpecies = { forms }
    }

    private val forms: List<String>? by lazy {
        val showdown = System.getProperty("aiengine.showdown")?.let(Path::of) ?: return@lazy null
        val jar = showdown.parent?.resolve("mods")?.takeIf(Files::isDirectory)?.let { mods ->
            Files.list(mods).use { files -> files.filter { it.fileName.toString().startsWith("mega_showdown") && it.toString().endsWith(".jar") }.findFirst().orElse(null) }
        } ?: return@lazy null
        val bundled = EngineDex.bundled()
        ZipFile(jar.toFile()).use { zip ->
            zip.entries().asSequence().filter { it.name.startsWith("data/cobblemon/species_additions/") && it.name.endsWith(".json") }
                .flatMap { entry ->
                    val addition = zip.getInputStream(entry).use { JsonParser.parseReader(it.reader()).asJsonObject }
                    val base = bundled.species(addition.get("target")?.asString?.substringAfter(':') ?: return@flatMap emptySequence())
                        ?: return@flatMap emptySequence()
                    addition.getAsJsonArray("forms")?.asSequence().orEmpty().map { it.asJsonObject }
                        .filter { form -> form.get("name")?.asString?.startsWith("Mega") == true }
                        .map { form -> showdown(base.name, base.num, form).toString() }
                }.toList()
        }.takeIf { it.isNotEmpty() }
    }

    private fun showdown(baseName: String, num: Int, form: JsonObject): JsonObject = JsonObject().apply {
        val forme = form.get("name").asString
        addProperty("num", num)
        addProperty("name", "$baseName-$forme")
        addProperty("baseSpecies", baseName)
        addProperty("forme", forme)
        add("types", JsonArray().apply {
            listOfNotNull(form.get("primaryType")?.asString, form.get("secondaryType")?.asString).forEach { add(it.replaceFirstChar(Char::uppercase)) }
        })
        val stats = form.getAsJsonObject("baseStats")
        add("baseStats", JsonObject().apply {
            mapOf("hp" to "hp", "atk" to "attack", "def" to "defence", "spa" to "special_attack", "spd" to "special_defence", "spe" to "speed")
                .forEach { (showdown, cobblemon) -> addProperty(showdown, stats.get(cobblemon).asInt) }
        })
        add("abilities", JsonObject().apply {
            form.getAsJsonArray("abilities")?.map { it.asString }.orEmpty().forEachIndexed { index, ability ->
                val hidden = ability.startsWith("h:")
                addProperty(if (hidden) "H" else index.toString(), ability.removePrefix("h:"))
            }
        })
        form.get("weight")?.asDouble?.let { addProperty("weightkg", it / 10.0) }
        form.get("height")?.asDouble?.let { addProperty("heightm", it / 10.0) }
        addProperty("battleOnly", baseName)
    }
}
