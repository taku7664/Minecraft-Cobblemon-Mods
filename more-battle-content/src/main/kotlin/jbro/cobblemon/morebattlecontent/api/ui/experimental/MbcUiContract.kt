package jbro.cobblemon.morebattlecontent.api.ui.experimental

private val CONTRACT_ID_PATTERN = Regex("[a-z][a-z0-9_.-]*")

@ExperimentalMbcUi
@JvmInline
value class MbcUiScreenId(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MbcUI screen id: $value" }
    }
}

@ExperimentalMbcUi
@JvmInline
value class MbcUiComponentId(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MbcUI component id: $value" }
    }
}

@ExperimentalMbcUi
@JvmInline
value class MbcUiActionId(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MbcUI action id: $value" }
    }
}

@ExperimentalMbcUi
@JvmInline
value class MbcUiStateKey(val value: String) {
    init {
        require(CONTRACT_ID_PATTERN.matches(value)) { "Invalid MbcUI state key: $value" }
    }
}

@ExperimentalMbcUi
sealed interface MbcUiNode {
    val id: MbcUiComponentId
    val visibleWhen: MbcUiStateKey?
}

@ExperimentalMbcUi
data class MbcUiGroup(
    override val id: MbcUiComponentId,
    val children: List<MbcUiNode>,
    override val visibleWhen: MbcUiStateKey? = null
) : MbcUiNode

@ExperimentalMbcUi
data class MbcUiText(
    override val id: MbcUiComponentId,
    val textKey: String,
    override val visibleWhen: MbcUiStateKey? = null
) : MbcUiNode {
    init {
        require(textKey.isNotBlank()) { "MbcUI text key must not be blank" }
    }
}

@ExperimentalMbcUi
data class MbcUiAction(
    override val id: MbcUiComponentId,
    val labelKey: String,
    val actionId: MbcUiActionId,
    val enabledWhen: MbcUiStateKey? = null,
    override val visibleWhen: MbcUiStateKey? = null
) : MbcUiNode {
    init {
        require(labelKey.isNotBlank()) { "MbcUI action label key must not be blank" }
    }
}

@ExperimentalMbcUi
data class MbcUiScreenDefinition(
    val id: MbcUiScreenId,
    val root: MbcUiNode,
    val declaredStateKeys: Set<MbcUiStateKey>,
    val registeredActions: Set<MbcUiActionId>
)
