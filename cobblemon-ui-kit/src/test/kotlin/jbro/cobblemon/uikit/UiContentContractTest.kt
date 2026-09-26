package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiContentContractTest {
    @Test
    fun `text and panel contracts reject unusable dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiTextSpec(Component.literal("Rules"), maxLines = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiPanelSpec(padding = UiInsets(-1, 0, 0, 0))
        }

        val panel = UiPanelSpec(title = Component.literal("Gym"), padding = UiInsets.all(4))
        assertEquals(UiRect(14, 28, 92, 38), panel.contentBounds(UiRect(10, 20, 100, 50), titleHeight = 4))
    }

    @Test
    fun `step track preserves semantic states and unique ids`() {
        val track = UiStepTrackSpec(
            listOf(
                UiStepSpec("coal", Component.literal("Coal"), UiStepState.CLEARED),
                UiStepSpec("forest", Component.literal("Forest"), UiStepState.ACTIVE),
                UiStepSpec("cobble", Component.literal("Cobble"), UiStepState.LOCKED)
            )
        )

        assertEquals("forest", track.steps.single { it.state == UiStepState.ACTIVE }.id)
        assertThrows(IllegalArgumentException::class.java) {
            UiStepTrackSpec(listOf(track.steps.first(), track.steps.first()))
        }
    }

    @Test
    fun `ordered selection toggles and reorders within its limit`() {
        val state = UiOrderedSelectionState(
            options = listOf(
                UiChoiceOption("a", Component.literal("A")),
                UiChoiceOption("b", Component.literal("B")),
                UiChoiceOption("c", Component.literal("C"), enabled = false)
            ),
            maximumSelections = 2
        )

        assertTrue(state.toggle("a"))
        assertTrue(state.toggle("b"))
        assertFalse(state.toggle("c"))
        assertEquals(listOf("a", "b"), state.selectedIds)
        assertTrue(state.moveEarlier("b"))
        assertEquals(listOf("b", "a"), state.selectedIds)
        assertEquals(1, state.orderOf("b"))
        assertTrue(state.toggle("a"))
        assertEquals(listOf("b"), state.selectedIds)
    }

    @Test
    fun `render slots and persistent callouts require accessible labels`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiRenderSlotSpec(Component.literal(" "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiCalloutSpec(UiOverlayTone.WARNING, Component.literal(" "))
        }
    }
}
