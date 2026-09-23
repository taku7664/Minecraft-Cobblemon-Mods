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

/** Contains compatibility-sensitive turn completion without letting one failed step strand the request. */
internal inline fun attemptBattleDecisionCompletion(
    complete: () -> Unit,
    recover: (Throwable) -> Unit,
): Boolean = try {
    complete()
    true
} catch (failure: Exception) {
    recover(failure)
    false
} catch (failure: LinkageError) {
    recover(failure)
    false
}
