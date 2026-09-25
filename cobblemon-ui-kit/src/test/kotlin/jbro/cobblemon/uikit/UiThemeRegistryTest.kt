package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UiThemeRegistryTest {
    @Test
    fun `theme snapshot copies caller maps`() {
        val metrics = CobblemonUiDefaultTheme.metrics.toMutableMap()
        val styles = CobblemonUiDefaultTheme.styles.toMutableMap()
        val snapshot = UiThemeSnapshot.create(
            id = "copy-test",
            colors = CobblemonUiDefaultTheme.colors,
            typography = CobblemonUiDefaultTheme.typography,
            spacing = CobblemonUiDefaultTheme.spacing,
            metrics = metrics,
            styles = styles
        )

        metrics.clear()
        styles.clear()

        assertEquals(24, snapshot.metrics(UiControlSize.MEDIUM).height)
        assertEquals(
            CobblemonUiDefaultTheme.snapshot.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL),
            snapshot.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL)
        )
    }

    @Test
    fun `registry swaps whole immutable snapshots`() {
        val initial = CobblemonUiDefaultTheme.snapshot
        val replacement = UiThemeSnapshot.create(
            id = "replacement",
            colors = initial.colors.copy(accentPrimary = 0xFF00AAFF.toInt()),
            typography = initial.typography,
            spacing = initial.spacing,
            metrics = CobblemonUiDefaultTheme.metrics,
            styles = CobblemonUiDefaultTheme.styles
        )
        val registry = UiThemeRegistry(initial)

        registry.install(replacement)

        assertNotSame(initial, registry.snapshot())
        assertEquals("replacement", registry.snapshot().id)
    }

    @Test
    fun `theme requires every size and widget state`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiThemeSnapshot.create(
                id = "missing-state",
                colors = CobblemonUiDefaultTheme.colors,
                typography = CobblemonUiDefaultTheme.typography,
                spacing = CobblemonUiDefaultTheme.spacing,
                metrics = CobblemonUiDefaultTheme.metrics - UiControlSize.SMALL,
                styles = CobblemonUiDefaultTheme.styles - (UiButtonVariant.PRIMARY to UiWidgetState.FOCUS)
            )
        }
    }
}
