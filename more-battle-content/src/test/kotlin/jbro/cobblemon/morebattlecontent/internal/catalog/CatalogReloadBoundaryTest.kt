package jbro.cobblemon.morebattlecontent.internal.catalog

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CatalogReloadBoundaryTest {
    @Test
    fun `runtime reload failure is reported without escaping`() {
        val failure = IllegalStateException("resource listing failed")
        var reported: Throwable? = null

        runCatalogReloadSafely(
            reload = { throw failure },
            reportFailure = { reported = it },
        )

        assertSame(failure, reported)
    }

    @Test
    fun `linkage failure and broken reporter cannot abort outer reload`() {
        runCatalogReloadSafely(
            reload = { throw NoSuchMethodError("resource API drift") },
            reportFailure = { throw IllegalStateException("logger unavailable") },
        )
    }
}
