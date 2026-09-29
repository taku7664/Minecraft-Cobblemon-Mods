package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiAdvancedWidgetContractTest {
    private val options = listOf(
        UiChoiceOption("normal", Component.literal("Normal")),
        UiChoiceOption("locked", Component.literal("Locked"), enabled = false),
        UiChoiceOption("master", Component.literal("Master"))
    )

    @Test
    fun `selection state rejects disabled choices and exposes the selected value`() {
        val state = UiSelectionState(options, selectedIndex = 0)

        assertFalse(state.select(1))
        assertTrue(state.select(2))
        assertEquals("master", state.selected.id)
    }

    @Test
    fun `combo box requires options and a valid selection`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiComboBoxSpec(Component.literal("Rank"), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiComboBoxSpec(Component.literal("Rank"), options, selectedIndex = 4)
        }
    }

    @Test
    fun `card and stat rows preserve display data and clamp visual progress`() {
        val card = UiCardSpec(Component.literal("Oreburgh Gym"), Component.literal("Level 14"))
        val stat = UiStatRowSpec(Component.literal("Badges"), Component.literal("9/8"), progress = 1.2f)

        assertEquals("Oreburgh Gym", card.title.string)
        assertEquals(1f, stat.displayProgress)
        assertThrows(IllegalArgumentException::class.java) {
            UiCheckboxSpec(Component.literal(" "), checked = false)
        }
    }
}
