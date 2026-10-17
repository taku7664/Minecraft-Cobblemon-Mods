package jbro.cobblemon.toitem.server

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.api.storage.party.PartyStore
import com.cobblemon.mod.common.api.storage.pc.PCBox
import com.cobblemon.mod.common.api.storage.pc.PCPosition
import com.cobblemon.mod.common.api.storage.pc.PCStore
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.server.level.ServerPlayer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class StoredPokemonLookupTest {
    private fun party() = object : PartyStore(UUID.randomUUID()) {
        override fun getObservingPlayers(): List<ServerPlayer> = emptyList()
    }

    private fun pc() = object : PCStore(UUID.randomUUID(), Component.literal("test")) {
        override fun getObservingPlayers(): List<ServerPlayer> = emptyList()
    }.apply { boxes += PCBox(this) }

    @Test fun `PC swap followed by itemization ignores the stale UUID index and still blocks the remaining Pokemon`() {
        val party = party()
        val pc = pc()
        val fromParty = Pokemon()
        val fromPc = Pokemon()
        val partyPosition = PartyPosition(0)
        val pcPosition = PCPosition(0, 0)
        party[partyPosition] = fromParty
        pc[pcPosition] = fromPc

        // The exact write order in Cobblemon 1.8.1 SwapPCPartyPokemonHandler.
        party[partyPosition] = fromPc
        pc[pcPosition] = fromParty
        assertTrue(party.remove(fromPc)) // Itemization removes the real party entry.

        assertSame(fromPc, pc[fromPc.uuid], "The upstream stale-index bug must be reproduced")
        assertNull(party[partyPosition])
        assertSame(fromParty, pc[pcPosition])
        assertFalse(StoredPokemonLookup.contains(fromPc.uuid, party, pc))
        assertTrue(StoredPokemonLookup.contains(fromParty.uuid, party, pc))
    }

    @Test fun `a real PC occupant is blocked even when its UUID is absent from the index`() {
        val pc = pc()
        val pokemon = Pokemon()
        pc.boxes[0][0] = pokemon
        assertNull(pc[pokemon.uuid])
        assertTrue(StoredPokemonLookup.contains(pokemon.uuid, party(), pc))
    }

    @Test fun `a real party occupant is blocked while another Pokemon of the same species is allowed`() {
        val party = party()
        val pokemon = Pokemon()
        party[PartyPosition(0)] = pokemon
        assertTrue(StoredPokemonLookup.contains(pokemon.uuid, party, pc()))
        assertFalse(StoredPokemonLookup.contains(Pokemon().uuid, party, pc()))
    }

    companion object {
        private var previousSpecies = emptyMap<ResourceLocation, Species>()

        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            previousSpecies = PokemonSpecies.species.associateBy { it.resourceIdentifier }
            val species = Species().apply {
                name = "Mankey"
                resourceIdentifier = ResourceLocation.parse("cobblemon:mankey")
                implemented = true
                initialize()
            }
            PokemonSpecies.reload(mapOf(species.resourceIdentifier to species))
        }

        @JvmStatic @AfterAll fun restoreSpecies() { PokemonSpecies.reload(previousSpecies) }
    }
}
