package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.IdentityHashMap

internal fun deepestDistinctCause(failure: Throwable, maximumDepth: Int = MAX_CAUSE_DEPTH): Throwable {
    require(maximumDepth > 0)
    val seen = IdentityHashMap<Throwable, Unit>()
    var current = failure
    repeat(maximumDepth) {
        seen[current] = Unit
        val next = try {
            current.cause
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        } ?: return current
        if (seen.containsKey(next)) return current
        current = next
    }
    return current
}

private const val MAX_CAUSE_DEPTH = 64
