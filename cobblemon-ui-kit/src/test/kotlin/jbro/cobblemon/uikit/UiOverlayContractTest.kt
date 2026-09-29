package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiOverlayContractTest {
    @Test
    fun `overlay placement flips above and clamps to the screen edge`() {
        val placed = UiOverlayPlacement.place(
            anchor = UiRect(80, 45, 10, 10),
            overlay = UiSize(30, 20),
            screen = UiSize(100, 60),
            margin = 4
        )

        assertEquals(UiRect(66, 21, 30, 20), placed)
    }

    @Test
    fun `toast queue expires deterministically`() {
        val queue = UiToastQueue()
        queue.push(UiToastSpec(Component.literal("Saved"), durationMillis = 1_000), nowMillis = 100)

        assertEquals("Saved", queue.active(1_099)?.message?.string)
        assertNull(queue.active(1_100))
    }

    @Test
    fun `overlay contracts reject unusable content`() {
        assertThrows(IllegalArgumentException::class.java) { UiTooltipSpec(Component.literal(" ")) }
        assertThrows(IllegalArgumentException::class.java) {
            UiDialogSpec(Component.literal("Title"), Component.literal(" "), Component.literal("OK"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiToastSpec(Component.literal("Saved"), durationMillis = 0)
        }
        assertTrue(UiTooltipSpec(Component.literal("Info")).body == null)
    }
}
