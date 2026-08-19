package jbro.cobblemon.morebattlecontent.internal.validation

internal object IdentifierSyntax {
    private val stableId = Regex("[a-z0-9][a-z0-9_.-]*")
    private val resourceId = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

    fun isStableId(value: String): Boolean = stableId.matches(value)

    fun isResourceId(value: String): Boolean = resourceId.matches(value)
}
