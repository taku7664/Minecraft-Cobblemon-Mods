package jbro.cobblemon.morebattlecontent.internal.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SavedDataLoadBoundaryTest {
    @Test
    fun `runtime and linkage failures produce the unavailable value with their original cause`() {
        val runtime = IllegalStateException("corrupt data")
        val linkage = NoSuchMethodError("NBT API drift")
        val reported = ArrayList<Throwable>()

        assertEquals("preserved", loadSavedDataSafely({ throw runtime }, reported::add) { "preserved" })
        assertEquals("preserved", loadSavedDataSafely({ throw linkage }, reported::add) { "preserved" })
        assertEquals(listOf(runtime, linkage), reported)
    }

    @Test
    fun `reporter failure cannot prevent the unavailable value from preserving data`() {
        assertEquals(
            "preserved",
            loadSavedDataSafely(
                load = { throw NoSuchMethodError("NBT API drift") },
                reportFailure = { throw NoSuchMethodError("logger API drift") },
                unavailable = { "preserved" },
            ),
        )
    }

    @Test
    fun `non-linkage fatal errors remain visible`() {
        val fatal = AssertionError("fatal")

        val thrown = assertThrows(AssertionError::class.java) {
            loadSavedDataSafely(load = { throw fatal }, reportFailure = {}, unavailable = { "preserved" })
        }

        assertSame(fatal, thrown)
    }
}
