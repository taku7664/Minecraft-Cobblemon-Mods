package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.lang.reflect.InvocationTargetException

internal inline fun <T> compatibilityCallOrElse(
    fallback: (Throwable) -> T,
    action: () -> T,
): T = try {
    action()
} catch (failure: InvocationTargetException) {
    when (val cause = failure.targetException) {
        is Exception -> fallback(cause)
        is LinkageError -> fallback(cause)
        is Error -> throw cause
        else -> fallback(cause)
    }
} catch (failure: Exception) {
    fallback(failure)
} catch (failure: LinkageError) {
    fallback(failure)
}

internal inline fun <T> compatibilityCallOrNull(action: () -> T): T? =
    compatibilityCallOrElse(fallback = { null }, action = action)
