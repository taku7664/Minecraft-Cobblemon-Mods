package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.FormData
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class Cobblemon173TowerRegisteredTeamSnapshotStoreTest {
    @Test
    fun `snapshot registration preserves legendary class identity`() {
        val species = Species().also {
            it.name = "Articuno"
            it.resourceIdentifier = ResourceLocation.parse("cobblemon:articuno")
            it.implemented = true
            it.labels += "legendary"
            it.initialize()
        }
        val pokemon = Pokemon().apply {
            uuid = UUID.randomUUID()
            this.species = species
        }

        val registration = pokemon.toTowerPokemonRegistration()

        assertTrue(registration.legendaryClass)
    }

    @Test
    fun `snapshot registration preserves a public alternate form`() {
        val alternate = FormData().also { formNameField.set(it, "wash") }
        val species = Species().also {
            it.name = "Rotom"
            it.resourceIdentifier = ResourceLocation.parse("cobblemon:rotom")
            it.implemented = true
            it.forms += alternate
            it.initialize()
        }
        val pokemon = Pokemon().apply {
            uuid = UUID.randomUUID()
            this.species = species
            form = alternate
        }

        val registration = pokemon.toTowerPokemonRegistration()

        assertEquals("wash", registration.formId)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
        }

        private val formNameField = FormData::class.java.getDeclaredField("name").apply { isAccessible = true }
    }
}
