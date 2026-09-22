package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.lang.reflect.InvocationTargetException

internal inline fun <T> compatibilityCallOrNull(action: () -> T): T? = try {
    action()
} catch (failure: InvocationTargetException) {
    when (val cause = failure.targetException) {
        is Exception -> null
        is LinkageError -> null
        is Error -> throw cause
        else -> null
    }
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}
