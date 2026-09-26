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
            val allowedTypes = if (kind == PublicTypeChangeKind.TERA) TERA_TYPES else DEFENSIVE_TYPES
            // Reflect Type omits the type value; typeless/custom values are also unrepresented.
            // Preserve UNKNOWN rather than reading the actual target or retaining stale types.
            val types = tokens.takeIf { it.isNotEmpty() && it.all(allowedTypes::contains) }?.toSet().orEmpty()
            return Cobblemon173PublicTypeChange(kind, types)
        }

        private val DEFENSIVE_TYPES = setOf("normal", "fire", "water", "electric", "grass", "ice", "fighting",
            "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy")
        private val TERA_TYPES = DEFENSIVE_TYPES + "stellar"
    }
}

/** Public temporary type state, separate from immutable species/form metadata. */
internal class Cobblemon173PublicTypeKnowledge {
    private data class State(
        val switchTypes: Set<String>,
        val base: Set<String>,
        val added: Set<String>? = null,
        val tera: Set<String>? = null,
        val preTera: Set<String>? = null,
        val stellarBoostedTypes: Set<String>? = null,
    ) {
        private fun ordinaryTypes(): Set<String> = if (base.isEmpty() || added?.isEmpty() == true) {
            emptySet()
        } else {
            base + added.orEmpty()
        }

        fun types(): Set<String> = when {
            tera == null -> ordinaryTypes()
            tera.singleOrNull() == STELLAR -> preTera.orEmpty()
            tera.size == 1 -> tera
            else -> emptySet()
        }

        fun baseStabTypes(): Set<String> = preTera ?: types()
        fun teraType(): String? = tera?.singleOrNull()
    }
    private val states = mutableMapOf<UUID, State>()

    fun apply(id: UUID, knownTypes: Set<String>, change: Cobblemon173PublicTypeChange): Set<String> {
        val previous = states[id] ?: State(knownTypes.toSet(), knownTypes.toSet())
        val updated = when (change.kind) {
            PublicTypeChangeKind.REPLACE -> previous.copy(base = change.types.toSet(), added = null)
            PublicTypeChangeKind.ADD -> previous.copy(added = change.types.toSet())
            PublicTypeChangeKind.REMOVE_ADDED -> previous.copy(added = null)
            PublicTypeChangeKind.TERA -> previous.copy(
                added = null,
                tera = change.types.toSet(),
                // Showdown clears pokemon.addedType as Tera activates. Only the replacement/base
                // types survive through getTypes(false, true) for ordinary retained STAB.
                preTera = previous.base,
                stellarBoostedTypes = if (change.types.singleOrNull() == STELLAR) emptySet() else null,
            )
        }
        states[id] = updated
        return updated.types()
    }

    fun baseStabTypes(id: UUID): Set<String>? = states[id]?.baseStabTypes()
    fun teraType(id: UUID): String? = states[id]?.teraType()
    fun stellarBoostedTypes(id: UUID): Set<String>? = states[id]
        ?.takeIf { it.teraType() == STELLAR }
        ?.stellarBoostedTypes

    /** Public move logs name the move, not always its callback-resolved type; unknown is conservative. */
    fun invalidateStellarBoostedTypes(id: UUID) {
        val previous = states[id]?.takeIf { it.teraType() == STELLAR } ?: return
        states[id] = previous.copy(stellarBoostedTypes = null)
    }

    fun clear(id: UUID): Set<String>? {
        val previous = states[id] ?: return null
        val tera = previous.tera
        return if (tera == null) {
            states.remove(id)
            previous.switchTypes
        } else {
            val restored = State(
                previous.switchTypes,
                previous.switchTypes,
                tera = tera,
                preTera = previous.switchTypes,
                stellarBoostedTypes = previous.stellarBoostedTypes,
            )
            states[id] = restored
            restored.types()
        }
    }
    fun reset() = states.clear()
    fun snapshot(): Map<UUID, Set<String>> = states.mapValues { it.value.types() }
    fun baseStabSnapshot(): Map<UUID, Set<String>> = states.mapValues { it.value.baseStabTypes() }
    fun teraSnapshot(): Map<UUID, String> = states.entries.mapNotNull { (id, state) ->
        state.tera?.singleOrNull()?.let { id to it }
    }.toMap()
    fun stellarBoostedTypeSnapshot(): Map<UUID, Set<String>?> = states
        .filterValues { it.teraType() == STELLAR }
        .mapValues { (_, state) -> state.stellarBoostedTypes?.toSet() }

    private companion object {
        const val STELLAR = "stellar"
    }
}
