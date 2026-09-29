package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component

data class UiChoiceOption(
    val id: String,
    val label: Component,
    val enabled: Boolean = true
) {
    init {
        require(id.matches(Regex("[a-z0-9_.-]+"))) { "Choice option id is invalid: $id" }
        require(label.string.isNotBlank()) { "Choice option label must not be blank" }
    }
}

class UiSelectionState(
    val options: List<UiChoiceOption>,
    selectedIndex: Int = 0
) {
    init {
        require(options.isNotEmpty()) { "Selection options must not be empty" }
        require(selectedIndex in options.indices) { "Selected choice is outside the options" }
        require(options.map(UiChoiceOption::id).distinct().size == options.size) { "Choice option ids must be unique" }
        require(options[selectedIndex].enabled) { "Selected choice must be enabled" }
    }

    var selectedIndex: Int = selectedIndex
        private set

    val selected: UiChoiceOption get() = options[selectedIndex]

    fun select(index: Int): Boolean {
        if (index !in options.indices || !options[index].enabled || index == selectedIndex) return false
        selectedIndex = index
        return true
    }
}

data class UiCheckboxSpec(
    val label: Component,
    val checked: Boolean,
    val enabled: Boolean = true
) {
    init {
        require(label.string.isNotBlank()) { "Checkbox label must not be blank" }
    }
}

data class UiRadioSpec(
    val option: UiChoiceOption,
    val selected: Boolean = false
)

data class UiComboBoxSpec(
    val label: Component,
    val options: List<UiChoiceOption>,
    val selectedIndex: Int = 0,
    val width: UiWidthPolicy = UiWidthPolicy.Content
) {
    init {
        require(label.string.isNotBlank()) { "Combo box label must not be blank" }
        require(options.isNotEmpty()) { "Combo box options must not be empty" }
        require(selectedIndex in options.indices) { "Combo box selection is outside the options" }
        require(options[selectedIndex].enabled) { "Combo box selection must be enabled" }
    }
}

data class UiCardSpec(
    val title: Component,
    val body: Component? = null,
    val icon: UiIcon? = null,
    val selected: Boolean = false,
    val tone: UiOverlayTone = UiOverlayTone.NEUTRAL
) {
    init {
        require(title.string.isNotBlank()) { "Card title must not be blank" }
        require(body == null || body.string.isNotBlank()) { "Card body must not be blank" }
    }
}

data class UiStatRowSpec(
    val label: Component,
    val value: Component,
    val progress: Float? = null
) {
    init {
        require(label.string.isNotBlank()) { "Stat row label must not be blank" }
        require(value.string.isNotBlank()) { "Stat row value must not be blank" }
        require(progress == null || progress.isFinite()) { "Stat row progress must be finite" }
    }

    val displayProgress: Float? = progress?.coerceIn(0f, 1f)
}
