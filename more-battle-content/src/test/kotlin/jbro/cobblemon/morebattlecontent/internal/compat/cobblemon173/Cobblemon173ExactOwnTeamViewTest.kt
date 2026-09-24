package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(4, build?.evs?.get("hp"))
        assertEquals(252, build?.evs?.get("spa"))
        assertEquals(252, build?.evs?.get("spe"))
        assertEquals(31, build?.ivs?.get("atk"))
        assertNull(build?.heldItemId)
    }
}
