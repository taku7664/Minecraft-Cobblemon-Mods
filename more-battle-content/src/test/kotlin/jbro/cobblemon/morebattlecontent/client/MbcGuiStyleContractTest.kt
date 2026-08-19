package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MbcGuiStyleContractTest {
    @Test
    fun `custom surface palette separates shell panels and button states`() {
        assertEquals(0xFF, MbcGuiPalette.BACKDROP ushr 24)
        assertEquals(0xFF, MbcGuiPalette.SHELL ushr 24)
        assertEquals(0xFF, MbcGuiPalette.PANEL ushr 24)
        assertNotEquals(MbcGuiPalette.SHELL, MbcGuiPalette.PANEL)
        assertNotEquals(MbcGuiPalette.BUTTON, MbcGuiPalette.BUTTON_HOVER)
        assertNotEquals(MbcGuiPalette.BUTTON, MbcGuiPalette.BUTTON_SELECTED)
        assertNotEquals(MbcGuiPalette.BUTTON, MbcGuiPalette.BUTTON_DISABLED)
    }

    @Test
    fun `custom surface exposes distinct semantic accents`() {
        val accents = setOf(
            MbcGuiPalette.ACCENT_PRIMARY,
            MbcGuiPalette.ACCENT_SECONDARY,
            MbcGuiPalette.ACCENT_BP,
            MbcGuiPalette.ACCENT_DANGER,
        )

        assertEquals(4, accents.size)
        assertTrue(accents.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `MBC screens override Minecraft background rendering`() {
        assertTrue(MbcScreen::class.java.isAssignableFrom(TowerPlayScreen::class.java))
        assertTrue(MbcScreen::class.java.isAssignableFrom(FactoryPlayScreen::class.java))
        assertTrue(MbcScreen::class.java.isAssignableFrom(MbcConfirmScreen::class.java))
        assertTrue(MbcScreen::class.java.declaredMethods.any { it.name == "renderBackground" })
    }

    @Test
    fun `content screens keep the shared battle tabs instead of a back screen`() {
        assertTrue(MbcTabbedContentScreen::class.java.isAssignableFrom(TowerPlayScreen::class.java))
        assertTrue(MbcTabbedContentScreen::class.java.isAssignableFrom(FactoryPlayScreen::class.java))
        assertTrue(MbcTabbedContentScreen::class.java.isAssignableFrom(PvpRoomListScreen::class.java))
        assertFalse(MbcTabbedContentScreen::class.java.isAssignableFrom(PvpRoomScreen::class.java))
    }
}
