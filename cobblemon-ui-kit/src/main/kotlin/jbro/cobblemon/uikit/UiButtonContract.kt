package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component

enum class UiButtonVariant {
    PRIMARY,
    SECONDARY,
    DANGER,
    GHOST,
    ICON
}

enum class UiControlSize {
    SMALL,
    MEDIUM,
    LARGE
}

enum class UiWidgetState {
    NORMAL,
    HOVER,
    FOCUS,
    PRESSED,
    DISABLED,
    SELECTED
}

enum class UiIconButtonShape(val shape: UiShape) {
    SQUARE(UiShape.Rectangle),
    CIRCLE(UiShape.Circle),
    DIAMOND(UiShape.Diamond)
}

sealed interface UiWidthPolicy {
    data object Content : UiWidthPolicy
    data object Fill : UiWidthPolicy

    data class Fixed(val pixels: Int) : UiWidthPolicy {
        init {
            require(pixels > 0) { "Fixed widget width must be positive" }
        }
    }
}

data class UiIcon(val namespace: String, val path: String) {
    init {
        require(ID_PART.matches(namespace)) { "Invalid icon namespace: $namespace" }
        require(RESOURCE_PATH.matches(path)) { "Invalid icon path: $path" }
    }

    private companion object {
        val ID_PART = Regex("[a-z0-9_.-]+")
        val RESOURCE_PATH = Regex("[a-z0-9/._-]+")
    }
}

data class UiButtonSpec(
    val title: Component,
    val supportingText: Component? = null,
    val icon: UiIcon? = null,
    val variant: UiButtonVariant = UiButtonVariant.PRIMARY,
    val size: UiControlSize = UiControlSize.MEDIUM,
    val width: UiWidthPolicy = UiWidthPolicy.Content,
    val selected: Boolean = false,
    val surfaceOverrides: UiSurfaceOverrides = UiSurfaceOverrides(),
    val textShadow: Boolean? = null,
    val iconOnly: Boolean = false,
    val iconButtonShape: UiIconButtonShape = UiIconButtonShape.SQUARE
) {
    init {
        require(title.string.isNotBlank()) { "Button title must not be blank" }
        if (iconOnly) {
            require(icon != null) { "Icon-only button must provide an icon" }
            require(variant == UiButtonVariant.ICON) { "Icon-only button must use the icon variant" }
            require(supportingText == null) { "Icon-only button must not provide supporting text" }
        }
    }

    fun resolveWidth(contentWidth: Int, availableWidth: Int, theme: UiThemeSnapshot): Int {
        require(contentWidth >= 0) { "Content width must not be negative" }
        require(availableWidth > 0) { "Available width must be positive" }
        val metrics = theme.metrics(size)
        val iconWidth = if (icon == null) 0 else metrics.iconSize + metrics.iconGap
        val naturalWidth = contentWidth + iconWidth + metrics.horizontalPadding * 2
        if (iconOnly) return resolveHeight(theme).coerceAtMost(availableWidth)
        return when (val policy = width) {
            UiWidthPolicy.Content -> naturalWidth.coerceAtMost(availableWidth)
            UiWidthPolicy.Fill -> availableWidth
            is UiWidthPolicy.Fixed -> policy.pixels.coerceAtMost(availableWidth)
        }
    }

    fun resolveHeight(theme: UiThemeSnapshot): Int =
        if (supportingText == null) theme.metrics(size).height else theme.metrics(size).supportingHeight

    fun resolveTextShadow(style: UiButtonStyle): Boolean = textShadow ?: style.textShadow

    fun resolveSurface(style: UiButtonStyle): UiSurfaceStyle {
        val resolved = style.surface.resolve(surfaceOverrides)
        return if (iconOnly) resolved.copy(shape = iconButtonShape.shape) else resolved
    }

    companion object {
        fun iconOnly(
            label: Component,
            icon: UiIcon,
            shape: UiIconButtonShape = UiIconButtonShape.SQUARE,
            size: UiControlSize = UiControlSize.MEDIUM,
            selected: Boolean = false,
            surfaceOverrides: UiSurfaceOverrides = UiSurfaceOverrides()
        ): UiButtonSpec = UiButtonSpec(
            title = label,
            icon = icon,
            variant = UiButtonVariant.ICON,
            size = size,
            selected = selected,
            surfaceOverrides = surfaceOverrides,
            iconOnly = true,
            iconButtonShape = shape
        )
    }
}
