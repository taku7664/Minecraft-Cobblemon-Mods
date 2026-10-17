package jbro.cobblemon.toitem.server

import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID

/** Storage slots are authoritative; Cobblemon's UUID index can retain entries after a PC/party swap. */
internal object StoredPokemonLookup {
    fun contains(id: UUID, party: Iterable<Pokemon>, pc: Iterable<Pokemon>): Boolean =
        party.any { it.uuid == id } || pc.any { it.uuid == id }
}
