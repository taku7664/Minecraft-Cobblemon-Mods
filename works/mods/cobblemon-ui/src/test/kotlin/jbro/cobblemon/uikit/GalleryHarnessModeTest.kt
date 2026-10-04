package jbro.cobblemon.uikit

import jbro.cobblemon.uikit.client.GalleryHarnessConfig
import jbro.cobblemon.uikit.client.GalleryCaptureLifecycle
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

    @Test
    fun `capture configuration selects an exact theme preset`() {
        val config = GalleryHarnessConfig.fromEnvironment(
            mapOf(
                "COBBLEMON_UI_KIT_CAPTURE_WORLD" to "1",
                "COBBLEMON_UI_KIT_THEME" to "hoenn_pixel"
            )
        )

        assertEquals(GalleryHarnessMode.CAPTURE, config.mode)
        assertEquals(UiThemePreset.HOENN_PIXEL, config.preset)
    }

    @Test
    fun `unknown capture theme falls back to the league baseline`() {
        val config = GalleryHarnessConfig.fromEnvironment(
            mapOf(
                "COBBLEMON_UI_KIT_CAPTURE_WORLD" to "1",
                "COBBLEMON_UI_KIT_THEME" to "hisui"
            )
        )

        assertEquals(UiThemePreset.LEAGUE_NEON, config.preset)
    }

    @Test
    fun `capture all configuration walks every built in preset in stable order`() {
        val config = GalleryHarnessConfig.fromEnvironment(
            mapOf(
                "COBBLEMON_UI_KIT_CAPTURE_WORLD" to "1",
                "COBBLEMON_UI_KIT_CAPTURE_ALL_THEMES" to "1"
            )
        )

        assertEquals(UiThemePreset.entries, config.capturePresets)
    }

    @Test
    fun `snapshot warning acknowledgement requires explicit development opt in`() {
        val disabled = GalleryHarnessConfig.fromEnvironment(
            mapOf("COBBLEMON_UI_KIT_CAPTURE_WORLD" to "1")
        )
        val enabled = GalleryHarnessConfig.fromEnvironment(
            mapOf(
                "COBBLEMON_UI_KIT_CAPTURE_WORLD" to "1",
                "COBBLEMON_UI_KIT_ACCEPT_SNAPSHOT_WARNING" to "1"
            )
        )

        assertEquals(false, disabled.acceptSnapshotWarning)
        assertEquals(true, enabled.acceptSnapshotWarning)
    }

    @Test
    fun `capture completion is terminal and idempotent across later ticks`() {
        val lifecycle = GalleryCaptureLifecycle()

        assertEquals(false, lifecycle.isFinished)
        assertEquals(true, lifecycle.finish())
        assertEquals(true, lifecycle.isFinished)
        assertEquals(false, lifecycle.finish())
    }
}
