package jbro.cobblemon.mcc.api.ui.experimental

@RequiresOptIn(
    message = "MccUI is an experimental spike contract and may change before the multi-screen promotion gate.",
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
annotation class ExperimentalMccUi
