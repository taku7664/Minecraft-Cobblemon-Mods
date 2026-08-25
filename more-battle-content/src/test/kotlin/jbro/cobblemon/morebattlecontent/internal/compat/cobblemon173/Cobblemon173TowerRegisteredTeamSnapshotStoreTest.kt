package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertTrue
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

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
        }
    }
}
