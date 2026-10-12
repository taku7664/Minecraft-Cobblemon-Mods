package jbro.cobblemon.toitem.server

import java.util.UUID

/** Checks independent of client trust and storage mutation. */
internal object TransferRules {
    fun actorAllowed(alive: Boolean, spectator: Boolean, battling: Boolean, permitted: Boolean) =
        alive && !spectator && !battling && permitted

    fun sourceMatches(count: Int, expected: UUID, actual: UUID?) = count == 1 && expected == actual

    fun restoreSlot(requested: Int?, occupied: List<Boolean>): Int? =
        if (requested == null) occupied.indexOfFirst { !it }.takeIf { it >= 0 }
        else requested.takeIf { it in occupied.indices && !occupied[it] }
}
