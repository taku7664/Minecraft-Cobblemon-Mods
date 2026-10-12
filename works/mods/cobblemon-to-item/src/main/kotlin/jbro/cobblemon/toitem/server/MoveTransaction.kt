package jbro.cobblemon.toitem.server

/** Server-thread move; the destination must be prepared before the origin is removed. */
internal object MoveTransaction {
    fun execute(remove: () -> Boolean, deliver: () -> Boolean, rollback: () -> Unit): Boolean {
        try {
            if (!remove()) return false
            if (deliver()) return true
        } catch (failure: Exception) {
            try { rollback() } catch (rollbackFailure: Exception) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
        rollback()
        return false
    }
}
