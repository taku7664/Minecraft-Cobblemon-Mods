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
    val textShadow: Boolean? = null
) {
    init {
        require(title.string.isNotBlank()) { "Button title must not be blank" }
    }

    fun resolveWidth(contentWidth: Int, availableWidth: Int, theme: UiThemeSnapshot): Int {
        require(contentWidth >= 0) { "Content width must not be negative" }
        require(availableWidth > 0) { "Available width must be positive" }
        val metrics = theme.metrics(size)
        val iconWidth = if (icon == null) 0 else metrics.iconSize + metrics.iconGap
        val naturalWidth = contentWidth + iconWidth + metrics.horizontalPadding * 2
        return when (val policy = width) {
            UiWidthPolicy.Content -> naturalWidth.coerceAtMost(availableWidth)
            UiWidthPolicy.Fill -> availableWidth
            is UiWidthPolicy.Fixed -> policy.pixels.coerceAtMost(availableWidth)
        }
    }

    fun resolveHeight(theme: UiThemeSnapshot): Int =
        if (supportingText == null) theme.metrics(size).height else theme.metrics(size).supportingHeight

    fun resolveTextShadow(style: UiButtonStyle): Boolean = textShadow ?: style.textShadow
}
