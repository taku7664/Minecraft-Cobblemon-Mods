package jbro.cobblemon.morebattlecontent.api.ui.experimental

@ExperimentalMbcUi
sealed interface MbcUiContractIssue {
    val componentId: MbcUiComponentId

    data class DuplicateComponentId(
        override val componentId: MbcUiComponentId
    ) : MbcUiContractIssue

    data class UnknownAction(
        override val componentId: MbcUiComponentId,
        val actionId: MbcUiActionId
    ) : MbcUiContractIssue

    data class UnknownStateKey(
        override val componentId: MbcUiComponentId,
        val stateKey: MbcUiStateKey
    ) : MbcUiContractIssue
}

@ExperimentalMbcUi
object MbcUiContractValidator {
    fun validate(definition: MbcUiScreenDefinition): List<MbcUiContractIssue> {
        val issues = mutableListOf<MbcUiContractIssue>()
        val componentIds = mutableSetOf<MbcUiComponentId>()

        fun validateStateReference(componentId: MbcUiComponentId, stateKey: MbcUiStateKey?) {
            if (stateKey != null && stateKey !in definition.declaredStateKeys) {
                issues += MbcUiContractIssue.UnknownStateKey(componentId, stateKey)
            }
        }

        fun visit(node: MbcUiNode) {
            if (!componentIds.add(node.id)) {
                issues += MbcUiContractIssue.DuplicateComponentId(node.id)
            }
            validateStateReference(node.id, node.visibleWhen)

            when (node) {
                is MbcUiAction -> {
                    if (node.actionId !in definition.registeredActions) {
                        issues += MbcUiContractIssue.UnknownAction(node.id, node.actionId)
                    }
                    validateStateReference(node.id, node.enabledWhen)
                }

                is MbcUiGroup -> node.children.forEach(::visit)
                is MbcUiText -> Unit
            }
        }

        visit(definition.root)
        return issues.toList()
    }
}
