package jbro.cobblemon.mcc.api.ui.experimental

@ExperimentalMccUi
sealed interface MccUiContractIssue {
    val componentId: MccUiComponentId

    data class DuplicateComponentId(
        override val componentId: MccUiComponentId
    ) : MccUiContractIssue

    data class UnknownAction(
        override val componentId: MccUiComponentId,
        val actionId: MccUiActionId
    ) : MccUiContractIssue

    data class UnknownStateKey(
        override val componentId: MccUiComponentId,
        val stateKey: MccUiStateKey
    ) : MccUiContractIssue
}

@ExperimentalMccUi
object MccUiContractValidator {
    fun validate(definition: MccUiScreenDefinition): List<MccUiContractIssue> {
        val issues = mutableListOf<MccUiContractIssue>()
        val componentIds = mutableSetOf<MccUiComponentId>()

        fun validateStateReference(componentId: MccUiComponentId, stateKey: MccUiStateKey?) {
            if (stateKey != null && stateKey !in definition.declaredStateKeys) {
                issues += MccUiContractIssue.UnknownStateKey(componentId, stateKey)
            }
        }

        fun visit(node: MccUiNode) {
            if (!componentIds.add(node.id)) {
                issues += MccUiContractIssue.DuplicateComponentId(node.id)
            }
            validateStateReference(node.id, node.visibleWhen)

            when (node) {
                is MccUiAction -> {
                    if (node.actionId !in definition.registeredActions) {
                        issues += MccUiContractIssue.UnknownAction(node.id, node.actionId)
                    }
                    validateStateReference(node.id, node.enabledWhen)
                }

                is MccUiGroup -> node.children.forEach(::visit)
                is MccUiText -> Unit
            }
        }

        visit(definition.root)
        return issues.toList()
    }
}
