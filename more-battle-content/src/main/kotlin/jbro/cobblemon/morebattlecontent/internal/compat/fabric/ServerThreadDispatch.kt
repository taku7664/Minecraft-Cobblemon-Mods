package jbro.cobblemon.morebattlecontent.internal.compat.fabric

internal fun dispatchToServerThread(
    isServerThread: Boolean,
    schedule: ((() -> Unit) -> Unit),
    action: () -> Unit,
) {
    if (isServerThread) action() else schedule(action)
}
