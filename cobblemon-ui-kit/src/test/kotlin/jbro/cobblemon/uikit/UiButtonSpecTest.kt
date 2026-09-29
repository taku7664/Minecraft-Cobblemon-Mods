package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiButtonSpecTest {
    private val theme = CobblemonUiDefaultTheme.snapshot

    @Test
    fun `content width follows text icon gap and semantic padding`() {
        val plain = UiButtonSpec(title = Component.literal("Start"), size = UiControlSize.MEDIUM)
        val withIcon = plain.copy(icon = UiIcon("cobblemon_ui_kit", "textures/gui/icons/start.png"))

        assertEquals(64, plain.resolveWidth(contentWidth = 40, availableWidth = 200, theme = theme))
        assertEquals(78, withIcon.resolveWidth(contentWidth = 40, availableWidth = 200, theme = theme))
    }

    @Test
    fun `fill width uses available width and fixed width remains explicit`() {
        val fill = UiButtonSpec(Component.literal("Start"), width = UiWidthPolicy.Fill)
        val fixed = UiButtonSpec(Component.literal("Start"), width = UiWidthPolicy.Fixed(96))

        assertEquals(180, fill.resolveWidth(contentWidth = 40, availableWidth = 180, theme = theme))
        assertEquals(96, fixed.resolveWidth(contentWidth = 40, availableWidth = 180, theme = theme))
    }

    @Test
    fun `invalid fixed width and blank title are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UiWidthPolicy.Fixed(0) }
        assertThrows(IllegalArgumentException::class.java) { UiButtonSpec(Component.literal(" ")) }
    }

    @Test
    fun `text shadow defaults off and can be enabled or suppressed per button`() {
        val baseStyle = theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL)
        val shadowStyle = baseStyle.copy(textShadow = true)
        val defaultButton = UiButtonSpec(Component.literal("Start"))

        assertFalse(defaultButton.resolveTextShadow(baseStyle))
        assertTrue(defaultButton.resolveTextShadow(shadowStyle))
        assertTrue(defaultButton.copy(textShadow = true).resolveTextShadow(baseStyle))
        assertFalse(defaultButton.copy(textShadow = false).resolveTextShadow(shadowStyle))
    }

    @Test
    fun `icon-only presets stay square and retain an accessible label`() {
        val button = UiButtonSpec.iconOnly(
            label = Component.literal("Information"),
            icon = UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
            shape = UiIconButtonShape.CIRCLE,
            size = UiControlSize.MEDIUM
        )
        val style = theme.style(UiButtonVariant.ICON, UiWidgetState.NORMAL)

        assertEquals(24, button.resolveWidth(contentWidth = 200, availableWidth = 80, theme = theme))
        assertEquals(UiShape.Circle, button.resolveSurface(style).shape)
        assertEquals("Information", button.title.string)
        assertTrue(button.iconOnly)
    }

    @Test
    fun `icon-only buttons require an icon and icon variant`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiButtonSpec(Component.literal("Missing"), iconOnly = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiButtonSpec(
                Component.literal("Wrong variant"),
                icon = UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                iconOnly = true,
                variant = UiButtonVariant.PRIMARY
            )
        }
    }
}
