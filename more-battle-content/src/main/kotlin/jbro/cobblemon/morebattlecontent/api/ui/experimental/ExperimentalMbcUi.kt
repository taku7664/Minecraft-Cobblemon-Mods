package jbro.cobblemon.morebattlecontent.api.ui.experimental

@RequiresOptIn(
    message = "MbcUI is an experimental spike contract and may change before the multi-screen promotion gate.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS
)
annotation class ExperimentalMbcUi
