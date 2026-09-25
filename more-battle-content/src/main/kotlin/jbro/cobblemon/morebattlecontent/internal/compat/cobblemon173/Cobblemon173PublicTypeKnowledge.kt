package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage
import java.util.UUID

internal enum class PublicTypeChangeKind { REPLACE, ADD, REMOVE_ADDED, TERA }

internal data class Cobblemon173PublicTypeChange(val kind: PublicTypeChangeKind, val types: Set<String>) {
    companion object {
        fun fromMessage(message: BattleMessage): Cobblemon173PublicTypeChange? {
            val effect = message.argumentAt(1)
            val kind = when {
                message.id == "-terastallize" -> PublicTypeChangeKind.TERA
                message.id == "-start" && effect == "typechange" -> PublicTypeChangeKind.REPLACE
                message.id == "-start" && effect == "typeadd" -> PublicTypeChangeKind.ADD
                message.id == "-end" && effect == "typeadd" -> PublicTypeChangeKind.REMOVE_ADDED
                else -> return null
            }
            val typeValue = if (kind == PublicTypeChangeKind.TERA) message.argumentAt(1) else message.argumentAt(2)
            val tokens = typeValue?.lowercase()?.split('/').orEmpty()
            // Reflect Type omits the type value; typeless/custom values are also unrepresented.
            // Preserve UNKNOWN rather than reading the actual target or retaining stale types.
            val types = tokens.takeIf { it.isNotEmpty() && it.all(KNOWN_TYPES::contains) }?.toSet().orEmpty()
            return Cobblemon173PublicTypeChange(kind, types)
        }

        private val KNOWN_TYPES = setOf("normal", "fire", "water", "electric", "grass", "ice", "fighting",
            "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy")
    }
}

/** Public temporary type state, separate from immutable species/form metadata. */
internal class Cobblemon173PublicTypeKnowledge {
    private data class State(
        val switchTypes: Set<String>,
        val base: Set<String>,
        val added: Set<String>? = null,
        val tera: Set<String>? = null,
    ) {
        fun types(): Set<String> = tera ?: if (base.isEmpty() || added?.isEmpty() == true) {
            emptySet()
        } else {
            base + added.orEmpty()
        }
    }
    private val states = mutableMapOf<UUID, State>()

    fun apply(id: UUID, knownTypes: Set<String>, change: Cobblemon173PublicTypeChange): Set<String> {
        val previous = states[id] ?: State(knownTypes.toSet(), knownTypes.toSet())
        val updated = when (change.kind) {
            PublicTypeChangeKind.REPLACE -> previous.copy(base = change.types.toSet(), added = null)
            PublicTypeChangeKind.ADD -> previous.copy(added = change.types.toSet())
            PublicTypeChangeKind.REMOVE_ADDED -> previous.copy(added = null)
            PublicTypeChangeKind.TERA -> previous.copy(added = null, tera = change.types.toSet())
        }
        states[id] = updated
        return updated.types()
    }

    fun clear(id: UUID): Set<String>? {
        val previous = states[id] ?: return null
        val tera = previous.tera
        return if (tera == null) {
            states.remove(id)
            previous.switchTypes
        } else {
            states[id] = State(previous.switchTypes, previous.switchTypes, tera = tera)
            tera
        }
    }
    fun reset() = states.clear()
    fun snapshot(): Map<UUID, Set<String>> = states.mapValues { it.value.types() }
    fun teraSnapshot(): Map<UUID, String> = states.entries.mapNotNull { (id, state) ->
        state.tera?.singleOrNull()?.let { id to it }
    }.toMap()
}
