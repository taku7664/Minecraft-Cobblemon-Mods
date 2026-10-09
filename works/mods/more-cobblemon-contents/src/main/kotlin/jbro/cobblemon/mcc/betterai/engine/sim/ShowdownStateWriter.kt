package jbro.cobblemon.mcc.betterai.engine.sim

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.lang.reflect.Modifier
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.dex.Species

/**
 * An engine battle written the way `State.serializeBattle` writes Showdown's, so [ShowdownStateReader] reads it back
 * into the same position. Tests and replays use it to stand in for a live battle the engine built itself; the game
 * reads Showdown's own serialization.
 */
object ShowdownStateWriter {
    /** [includeLog] carries the protocol log over, for comparing logs after a round trip. */
    fun write(battle: Battle, includeLog: Boolean = false): JsonObject {
        val out = JsonObject()
        val writer = Writer(battle)
        writer.fields(battle, out, BATTLE_SKIP)
        out.addProperty("gameType", battle.gameType)
        out.addProperty("strictChoices", battle.strictChoices)
        out.add("prngSeed", ints(battle.prng.startingSeed))
        out.add("prng", ints(battle.prng.seed))
        out.add("field", JsonObject().also { writer.fields(battle.field, it, setOf("battle")) })
        out.add("sides", JsonArray().also { sides -> battle.sides.forEach { sides.add(writer.side(it)) } })
        out.add("queue", JsonArray().also { q -> battle.queue.list.forEach { q.add(writer.value(it)) } })
        out.add("log", JsonArray().also { a -> if (includeLog) battle.log.forEach { a.add(it) } })
        out.add("hints", JsonArray())
        return out
    }

    private fun ints(values: IntArray) = JsonArray().also { a -> values.forEach { a.add(it) } }

    private class Writer(val battle: Battle) {
        fun side(side: Side): JsonObject {
            val out = JsonObject()
            fields(side, out, SIDE_SKIP)
            out.addProperty("id", side.id)
            out.addProperty("n", side.n)
            out.addProperty("name", side.name)
            out.add("pokemon", JsonArray().also { team -> side.pokemon.forEach { team.add(pokemon(it)) } })
            // For each original team index, the Pokemon's 1-based position now.
            val order = side.team.map { set -> side.pokemon.indexOfFirst { it.set === set || it.set.uuid == set.uuid } + 1 }
            out.addProperty("team", if (order.size > 9) order.joinToString(",") else order.joinToString(""))
            out.add("choice", JsonObject().also { choice ->
                fields(side.choice, choice, setOf("switchIns"))
                choice.add("switchIns", JsonArray().also { a -> side.choice.switchIns.forEach { a.add(it) } })
            })
            return out
        }

        fun pokemon(pokemon: Pokemon): JsonObject {
            val out = JsonObject()
            fields(pokemon, out, POKEMON_SKIP)
            out.add("set", set(pokemon.set))
            if (pokemon.baseMoveSlots.size != pokemon.moveSlots.size ||
                pokemon.baseMoveSlots.indices.any { pokemon.baseMoveSlots[it] !== pokemon.moveSlots[it] }) {
                out.add("baseMoveSlots", value(pokemon.baseMoveSlots))
            }
            return out
        }

        private fun set(set: PokemonSet) = JsonObject().apply {
            addProperty("name", set.name)
            addProperty("species", set.species)
            add("moves", JsonArray().also { a -> set.moves.forEach { a.add(it) } })
            addProperty("ability", set.ability)
            addProperty("uuid", set.uuid)
            addProperty("item", set.item)
            addProperty("nature", set.nature)
            addProperty("gender", set.gender)
            addProperty("level", set.level)
            add("evs", JsonObject().also { o -> set.evs.forEach { (k, v) -> o.addProperty(k, v) } })
            add("ivs", JsonObject().also { o -> set.ivs.forEach { (k, v) -> o.addProperty(k, v) } })
            set.movesInfo?.let { info ->
                add("movesInfo", JsonArray().also { a -> info.forEach { a.add(JsonObject().apply { addProperty("pp", it[0]); addProperty("maxPp", it[1]) }) } })
            }
            set.teraType?.let { addProperty("teraType", it) }
            if (set.shiny) addProperty("shiny", true)
            set.happiness?.let { addProperty("happiness", it) }
            set.dynamaxLevel?.let { addProperty("dynamaxLevel", it) }
            if (set.gigantamax) addProperty("gigantamax", true)
            set.hpType?.let { addProperty("hpType", it) }
            set.pokeball?.let { addProperty("pokeball", it) }
        }

        fun fields(target: Any, out: JsonObject, skip: Set<String>) {
            for ((name, field) in fieldsOf(target.javaClass)) {
                if (name in skip) continue
                val v = field.get(target)
                if (v === Unit) continue
                val json = try { value(v) } catch (_: Unsupported) { continue }
                out.add(name, json)
            }
        }

        fun value(v: Any?): JsonElement = when (v) {
            null -> JsonNull.INSTANCE
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Pokemon -> JsonPrimitive("[Pokemon:${v.side.id}${POSITIONS[v.position]}]")
            is Side -> JsonPrimitive("[Side:${v.id}]")
            is Battle -> JsonPrimitive("[Battle]")
            is Field -> JsonPrimitive("[Field]")
            is Species -> JsonPrimitive("[Species:${v.id}]")
            is EffectState -> effectState(v)
            is ActiveMove -> activeMove(v)
            is LiteralHit -> throw Unsupported()
            is EffectLike -> effect(v)
            is IntArray -> ints(v)
            is MoveSlot -> JsonObject().also { fields(v, it, emptySet()) }
            // An entry that cannot be written is left out, never the whole map.
            is Map<*, *> -> JsonObject().also { o ->
                v.forEach { (k, x) -> if (x !== Unit) try { o.add(k.toString(), value(x)) } catch (_: Unsupported) {} }
            }
            is Iterable<*> -> JsonArray().also { a -> v.forEach { a.add(value(it)) } }
            is Function<*> -> throw Unsupported()
            else -> JsonObject().also { fields(v, it, OBJECT_SKIP) }
        }

        private fun effect(v: EffectLike): JsonElement {
            if (v.id.isEmpty()) return JsonObject().apply { addProperty("id", "") }
            val type = when (v.effectType) {
                "Ability" -> "Ability"
                "Item" -> "Item"
                "Move" -> "Move"
                "Pokemon" -> "Species"
                else -> "Condition"
            }
            return JsonPrimitive("[$type:${v.id}]")
        }

        private fun effectState(state: EffectState): JsonObject = JsonObject().apply {
            addProperty("id", state.id)
            state.duration?.let { addProperty("duration", it) }
            state.target?.let { add("target", value(it)) }
            state.source?.let { add("source", value(it)) }
            state.sourceSlot?.let { addProperty("sourceSlot", it) }
            state.sourceEffect?.let { try { add("sourceEffect", value(it)) } catch (_: Unsupported) {} }
            state.values.forEach { (k, x) -> if (x !== Unit) try { add(k, value(x)) } catch (_: Unsupported) {} }
        }

        /** Like Showdown's, a reference to the move and the fields that are not the move's own data. */
        private fun activeMove(move: ActiveMove): JsonObject {
            // A move made from data alone (Future Sight's moveData) is written as that data, as Showdown keeps it.
            if (move.template.hookKey.isEmpty()) {
                return move.template.raw.deepCopy().apply {
                    addProperty("id", move.template.id)
                    addProperty("effectType", "Move")
                }
            }
            return namedMove(move)
        }

        private fun namedMove(move: ActiveMove): JsonObject = JsonObject().apply {
            addProperty("move", "[Move:${move.template.id}]")
            addProperty("id", move.id)
            addProperty("hit", move.hit)
            addProperty("totalDamage", move.totalDamage)
            addProperty("sourceEffect", move.sourceEffect)
            if (move.isExternal) addProperty("isExternal", true)
            if (move.pranksterBoosted) addProperty("pranksterBoosted", true)
            if (move.hasBounced) addProperty("hasBounced", true)
            if (move.selfDropped) addProperty("selfDropped", true)
            if (move.isZOrMaxPowered) addProperty("isZOrMaxPowered", true)
            move.baseMove?.let { addProperty("baseMove", it) }
            if (move.type != move.template.type) addProperty("type", move.type)
            if (move.basePower != move.template.basePower) addProperty("basePower", move.basePower)
            if (move.category != move.template.category) addProperty("category", move.category)
            if (move.target != move.template.target) addProperty("target", move.target)
            if (move.priority != move.template.priority) addProperty("priority", move.priority)
            move.extra.forEach { (k, x) -> if (x !is Function<*>) try { add(k, value(x)) } catch (_: Unsupported) {} }
        }
    }

    private class Unsupported : RuntimeException(null, null, false, false)

    private val fieldCache = object : ClassValue<Map<String, java.lang.reflect.Field>>() {
        override fun computeValue(type: Class<*>): Map<String, java.lang.reflect.Field> {
            val fields = LinkedHashMap<String, java.lang.reflect.Field>()
            var c: Class<*>? = type
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.isSynthetic || f.name in fields || f.name.contains('$')) continue
                    f.isAccessible = true
                    fields[f.name] = f
                }
                c = c.superclass
            }
            return fields
        }
    }

    private fun fieldsOf(type: Class<*>): Map<String, java.lang.reflect.Field> = fieldCache.get(type)

    private const val POSITIONS = "abcdefghijklmnopqrstuvwx"

    private val BATTLE_SKIP = setOf(
        "dex", "options", "logEnabled", "log", "gameType", "activePerHalf", "prng", "sides", "field", "queue", "actions",
        "strictChoices", "effect", "effectState", "event", "eventDepth", "hints", "tracer", "missingHooks",
    )
    private val SIDE_SKIP = setOf("battle", "team", "pokemon", "choice", "activeRequest", "name", "n", "id")
    private val POKEMON_SKIP = setOf("side", "battle", "set", "name", "fullname", "level", "happiness", "baseMoveSlots")
    private val OBJECT_SKIP = setOf("battle")

    @Suppress("unused")
    private val referenced = Effect::class
}
