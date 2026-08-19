package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MbcPokemonPortraitIdentityTest {
    @Test
    fun `factory portrait keeps set identity species and form`() {
        val identity = MbcPokemonPortraitIdentity.factory(
            setId = "factory-rental-17",
            speciesId = "cobblemon:rotom",
            formId = "wash",
        )

        assertEquals("factory:factory-rental-17", identity.stateKey)
        assertEquals("cobblemon:rotom", identity.speciesId)
        assertEquals("wash", identity.formId)
    }

    @Test
    fun `swap offer token isolates animation state from rental set`() {
        val identity = MbcPokemonPortraitIdentity.offer(
            token = "offer-3",
            speciesId = "cobblemon:charizard",
            formId = null,
        )

        assertEquals("factory-offer:offer-3", identity.stateKey)
    }
}
