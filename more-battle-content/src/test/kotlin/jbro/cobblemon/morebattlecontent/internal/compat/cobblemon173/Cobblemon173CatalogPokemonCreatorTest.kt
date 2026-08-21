package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class Cobblemon173CatalogPokemonCreatorTest {
    @Test
    fun `preserves every alternate form referenced by factory and tower catalogs`() {
        referencedForms().forEach { (speciesId, formId) ->
            val species = requireNotNull(PokemonSpecies.getByIdentifier(ResourceLocation.parse(speciesId)))
            val targetForm = species.forms.first { it.name.equals(formId, ignoreCase = true) }
            val pokemon = Pokemon().apply {
                this.species = species
                form = targetForm
                updateAspects()
                updateForm()
            }

            assertFalse(pokemon.form == targetForm, "$speciesId $formId did not reproduce the missing-aspect reset")
            Cobblemon173CatalogPokemonCreator.preserve(pokemon, formId)

            assertTrue(pokemon.form == targetForm, "$speciesId $formId became ${pokemon.form.name}")
            assertTrue(
                pokemon.forcedAspects.containsAll(targetForm.aspects),
                "$speciesId $formId lost required aspects ${targetForm.aspects}",
            )
        }
    }

    companion object {
        private val catalogDirectories = listOf(
            "/data/cobblemon_more_battle_content/mbc-battle-factory/rental-sets",
            "/data/cobblemon_more_battle_content/mbc-battle-tower/pokemon-sets",
        )

        @JvmStatic
        @BeforeAll
        fun loadReferencedCobblemonSpecies() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            val formsBySpecies = referencedForms().groupBy({ it.first }, { it.second })
            cobblemonJar().use { jar ->
                val entries = jar.entries().asSequence()
                    .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                    .associateBy { it.name.substringAfterLast('/').removeSuffix(".json") }
                val species = formsBySpecies.map { (speciesId, formIds) ->
                    val id = ResourceLocation.parse(speciesId)
                    val entry = requireNotNull(entries[id.path]) { "Missing Cobblemon species JSON for $speciesId" }
                    val root = jar.getInputStream(entry).reader().use(JsonParser::parseReader).asJsonObject
                    id to minimalSpecies(id, root["name"].asString, formIds, root.getAsJsonArray("forms"))
                }.toMap()
                PokemonSpecies.reload(species)
            }
        }

        private fun minimalSpecies(
            id: ResourceLocation,
            name: String,
            formIds: List<String>,
            sourceForms: Iterable<com.google.gson.JsonElement>,
        ): Species = Species().also { species ->
            species.name = name
            species.resourceIdentifier = id
            species.implemented = true
            val requestedFormIds = formIds.distinct()
            sourceForms.map { it.asJsonObject }
                .filter { source ->
                    requestedFormIds.any { formId -> source["name"].asString.equals(formId, ignoreCase = true) }
                }
                .forEach { source ->
                    species.forms += FormData().also { form ->
                        formNameField.set(form, source["name"].asString)
                        form.aspects = source.getAsJsonArray("aspects").map { it.asString }.toMutableList()
                    }
                }
            species.initialize()
        }

        private fun referencedForms(): Set<Pair<String, String>> = buildSet {
            catalogDirectories.forEach { directory ->
                resourceFiles(directory).forEach { path ->
                    val root = Files.newBufferedReader(path).use(JsonParser::parseReader).asJsonObject
                    val sets = root.getAsJsonArray("rental_sets") ?: root.getAsJsonArray("pokemon_sets")
                    sets.forEach { element ->
                        val set = element.asJsonObject
                        if (set.has("form_id") && !set["form_id"].isJsonNull) {
                            add(set["species_id"].asString to set["form_id"].asString)
                        }
                    }
                }
            }
        }

        private fun resourceFiles(directory: String): List<Path> {
            val url = requireNotNull(Cobblemon173CatalogPokemonCreatorTest::class.java.getResource(directory)) {
                "Missing resource directory $directory"
            }
            return Files.list(Paths.get(url.toURI())).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
            }
        }

        private fun cobblemonJar(): JarFile {
            val codeSource = Paths.get(PokemonSpecies::class.java.protectionDomain.codeSource.location.toURI())
            if (codeSource.toFile().isFile) return JarFile(codeSource.toFile())
            val candidate = System.getProperty("java.class.path")
                .split(System.getProperty("path.separator"))
                .asSequence()
                .map(Paths::get)
                .filter { it.toFile().isFile && it.fileName.toString().contains("cobblemon", ignoreCase = true) }
                .firstOrNull { path ->
                    runCatching {
                        JarFile(path.toFile()).use { it.getEntry("data/cobblemon/species/generation4/rotom.json") != null }
                    }.getOrDefault(false)
                }
            return JarFile(requireNotNull(candidate) { "Could not locate the Cobblemon runtime JAR" }.toFile())
        }

        private val formNameField = FormData::class.java.getDeclaredField("name").apply { isAccessible = true }
    }
}
