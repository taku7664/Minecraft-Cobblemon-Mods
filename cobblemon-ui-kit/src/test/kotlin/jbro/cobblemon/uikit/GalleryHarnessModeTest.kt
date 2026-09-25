package jbro.cobblemon.uikit

import jbro.cobblemon.uikit.client.GalleryHarnessMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GalleryHarnessModeTest {
    @Test
    fun `capture mode takes precedence over manual mode`() {
        val mode = GalleryHarnessMode.fromEnvironment(
            mapOf(
                "COBBLEMON_UI_KIT_CAPTURE_WORLD" to "1",
                "COBBLEMON_UI_KIT_MANUAL_GALLERY" to "1"
            )
        )

        assertEquals(GalleryHarnessMode.CAPTURE, mode)
    }

    @Test
    fun `manual mode opens gallery without capture automation`() {
        val mode = GalleryHarnessMode.fromEnvironment(
            mapOf("COBBLEMON_UI_KIT_MANUAL_GALLERY" to "1")
        )

        assertEquals(GalleryHarnessMode.MANUAL, mode)
    }

    @Test
    fun `harness is off by default`() {
        assertEquals(GalleryHarnessMode.OFF, GalleryHarnessMode.fromEnvironment(emptyMap()))
    }
}
