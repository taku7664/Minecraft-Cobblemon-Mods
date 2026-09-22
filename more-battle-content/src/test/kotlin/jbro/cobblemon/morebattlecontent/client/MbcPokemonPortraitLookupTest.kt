package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MbcPokemonPortraitLookupTest {
    @Test
    fun `portrait fallback contains recoverable lookup failures`() {
        assertNull(portraitLookupOrNull<String> { error("party unavailable") })
        assertNull(portraitLookupOrNull<String> { throw NoSuchMethodError("Cobblemon API drift") })
    }

    @Test
    fun `portrait fallback does not hide fatal errors`() {
        assertThrows(AssertionError::class.java) {
            portraitLookupOrNull<String> { throw AssertionError("fatal") }
        }
    }
}
