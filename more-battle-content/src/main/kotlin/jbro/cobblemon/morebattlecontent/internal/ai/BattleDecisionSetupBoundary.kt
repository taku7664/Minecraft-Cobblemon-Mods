package jbro.cobblemon.morebattlecontent.internal.ai

/** Contains compatibility-sensitive turn preparation and hands failures to the required fallback path. */
internal inline fun <T> attemptBattleDecisionSetup(
    setup: () -> T,
    recover: (Throwable) -> Unit,
): T? = try {
    setup()
} catch (failure: Exception) {
    recover(failure)
    null
} catch (failure: LinkageError) {
    recover(failure)
    null
}
