package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class Cobblemon173ExactOwnTeamViewTest {
    @Test
    fun `captures the source set including effective hyper trained ivs`() {
        val pokemon = Pokemon()
        pokemon.evs[Stats.HP] = 4
        pokemon.evs[Stats.SPECIAL_ATTACK] = 252
        pokemon.evs[Stats.SPEED] = 252
        pokemon.ivs[Stats.ATTACK] = 3
        pokemon.ivs.setHyperTrainedIV(Stats.ATTACK, 31)
        val battlePokemon = BattlePokemon(pokemon)

        val team = Cobblemon173ExactOwnTeamView.from(listOf(battlePokemon))
        val build = team.buildFor(battlePokemon.uuid)

        assertEquals(pokemon.ability.name, build?.abilityId)
        assertEquals(pokemon.nature.name.toString(), build?.natureId)
        assertEquals(pokemon.gender.showdownName, build?.gender)
        assertEquals(pokemon.teraType.name, build?.teraTypeId)
        assertEquals(pokemon.form.showdownId(), build?.showdownSpeciesId)
        assertEquals(4, build?.evs?.get("hp"))
        assertEquals(252, build?.evs?.get("spa"))
        assertEquals(252, build?.evs?.get("spe"))
        assertEquals(31, build?.ivs?.get("atk"))
        assertNull(build?.heldItemId)
    }

    companion object {
        private var previousSpecies = emptyMap<ResourceLocation, Species>()

        // Pokemon() picks a random registered species, so the test must not rely on another test's registry.
        @JvmStatic
        @BeforeAll
        fun registerSpecies() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            previousSpecies = PokemonSpecies.species.associateBy { it.resourceIdentifier }
            val species = Species().also {
                it.name = "Bulbasaur"
                it.resourceIdentifier = ResourceLocation.parse("cobblemon:bulbasaur")
                it.implemented = true
                it.initialize()
            }
            PokemonSpecies.reload(mapOf(species.resourceIdentifier to species))
        }

        @JvmStatic
        @AfterAll
        fun restoreSpecies() { PokemonSpecies.reload(previousSpecies) }
    }
}
