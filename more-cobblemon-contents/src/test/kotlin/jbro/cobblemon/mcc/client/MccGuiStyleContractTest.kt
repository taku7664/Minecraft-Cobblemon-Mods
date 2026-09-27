package jbro.cobblemon.mcc.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccGuiStyleContractTest {
    @Test
    fun `custom surface palette separates shell panels and button states`() {
        assertEquals(0xFF, MccGuiPalette.BACKDROP ushr 24)
        assertEquals(0xFF, MccGuiPalette.SHELL ushr 24)
        assertEquals(0xFF, MccGuiPalette.PANEL ushr 24)
        assertNotEquals(MccGuiPalette.SHELL, MccGuiPalette.PANEL)
        assertNotEquals(MccGuiPalette.BUTTON, MccGuiPalette.BUTTON_HOVER)
        assertNotEquals(MccGuiPalette.BUTTON, MccGuiPalette.BUTTON_SELECTED)
        assertNotEquals(MccGuiPalette.BUTTON, MccGuiPalette.BUTTON_DISABLED)
    }

    @Test
    fun `custom surface exposes distinct semantic accents`() {
        val accents = setOf(
            MccGuiPalette.ACCENT_PRIMARY,
            MccGuiPalette.ACCENT_SECONDARY,
            MccGuiPalette.ACCENT_BP,
            MccGuiPalette.ACCENT_DANGER,
        )

        assertEquals(4, accents.size)
        assertTrue(accents.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `MCC screens override Minecraft background rendering`() {
        assertTrue(MccScreen::class.java.isAssignableFrom(MccConfirmScreen::class.java))
        assertTrue(MccScreen::class.java.declaredMethods.any { it.name == "renderBackground" })
    }
}
