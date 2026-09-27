package jbro.cobblemon.mcc.api.ui.experimental

private val CONTRACT_ID_PATTERN = Regex("[a-z][a-z0-9_.-]*")

@ExperimentalMccUi
@JvmInline
value class MccUiScreenId(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MccUI screen id: $value" }
    }
}

@ExperimentalMccUi
@JvmInline
value class MccUiComponentId(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MccUI component id: $value" }
    }
}

@ExperimentalMccUi
@JvmInline
value class MccUiActionId(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MccUI action id: $value" }
    }
}

@ExperimentalMccUi
@JvmInline
value class MccUiStateKey(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MccUI state key: $value" }
    }
}

@ExperimentalMccUi
sealed interface MccUiNode {
    val id: MccUiComponentId
    val visibleWhen: MccUiStateKey?
}

@ExperimentalMccUi
data class MccUiGroup(
    override val id: MccUiComponentId,
    val children: List<MccUiNode>,
    override val visibleWhen: MccUiStateKey? = null
) : MccUiNode

@ExperimentalMccUi
data class MccUiText(
    override val id: MccUiComponentId,
    val textKey: String,
    override val visibleWhen: MccUiStateKey? = null
) : MccUiNode {
    init {
        require(textKey.isNotBlank()) { "MccUI text key must not be blank" }
    }
}

@ExperimentalMccUi
data class MccUiAction(
    override val id: MccUiComponentId,
    val labelKey: String,
    val actionId: MccUiActionId,
    val enabledWhen: MccUiStateKey? = null,
    override val visibleWhen: MccUiStateKey? = null
) : MccUiNode {
    init {
        require(labelKey.isNotBlank()) { "MccUI action label key must not be blank" }
    }
}

@ExperimentalMccUi
data class MccUiScreenDefinition(
    val id: MccUiScreenId,
    val root: MccUiNode,
    val declaredStateKeys: Set<MccUiStateKey>,
    val registeredActions: Set<MccUiActionId>
)
