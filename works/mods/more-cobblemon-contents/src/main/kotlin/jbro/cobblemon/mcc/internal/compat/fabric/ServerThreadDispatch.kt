package jbro.cobblemon.mcc.internal.compat.fabric

fun dispatchToServerThread(
    isServerThread: Boolean,
    schedule: ((() -> Unit) -> Unit),
    action: () -> Unit,
) {
    if (isServerThread) action() else schedule(action)
}
