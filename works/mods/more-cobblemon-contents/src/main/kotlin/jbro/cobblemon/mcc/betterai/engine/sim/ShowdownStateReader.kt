package jbro.cobblemon.mcc.betterai.engine.sim

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.engine.dex.Species

/**
 * Port of `State.deserializeBattle` (`sim/state.js`): a battle Showdown serialized (`battle.toJSON()`) becomes an
 * engine battle in the same position, ready for the next choices.
 *
 * Like Showdown, it builds the battle from the serialized sets and then writes every serialized property onto the
 * object of the same name. The engine is a port with Showdown's names, so one reader covers every effect: a
 * volatile's data, a side condition's layers or a queued action are fields like any other. Only the structural
 * types the engine builds with constructor arguments are named here. Properties the engine does not have are
 * reported in [Result.unknownKeys] instead of failing.
 */
object ShowdownStateReader {
    class Result(val battle: Battle, val unknownKeys: Set<String>)

    /** [keepLog] keeps Showdown's protocol log, carried over from the serialized battle (referee tests only). */
    fun read(dex: EngineDex, json: String, keepLog: Boolean = false): Result =
        read(dex, JsonParser.parseString(json).asJsonObject, keepLog)

    fun read(dex: EngineDex, state: JsonObject, keepLog: Boolean = false): Result {
        val seed = ints(state.getAsJsonArray("prngSeed") ?: state.getAsJsonArray("prng"))
        val battle = Battle(dex, BattleOptions(
            gameType = state.get("gameType")?.asString ?: "singles",
            seed = seed,
            log = keepLog,
            strictChoices = state.get("strictChoices")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            deferStart = true,
        ))
        val sideStates = state.getAsJsonArray("sides").map { it.asJsonObject }
        for (sideState in sideStates) {
            val pokemon = sideState.getAsJsonArray("pokemon").map { it.asJsonObject }
            val sets = teamOrder(sideState, pokemon.size).map { position -> set(pokemon[position - 1].getAsJsonObject("set")) }
            battle.setPlayer(sideState.get("id").asString, sideState.get("name")?.asString ?: "", sets)
        }
        for ((i, sideState) in sideStates.withIndex()) {
            val side = battle.sides[i]
            val ordered = arrayOfNulls<Pokemon>(side.pokemon.size)
            for ((j, position) in teamOrder(sideState, side.pokemon.size).withIndex()) ordered[position - 1] = side.pokemon[j]
            side.pokemon = ordered.map { requireNotNull(it) { "Serialized team order skips a Pokemon" } }.toMutableList()
        }
        val reader = Reader(battle)
        reader.fill(battle, state, BATTLE)
        reader.fill(battle.field, state.getAsJsonObject("field"), FIELD)
        for ((i, sideState) in sideStates.withIndex()) {
            val side = battle.sides[i]
            reader.fill(side, sideState, SIDE)
            for ((j, pokemonState) in sideState.getAsJsonArray("pokemon").withIndex()) {
                val pokemon = side.pokemon[j]
                reader.fill(pokemon, pokemonState.asJsonObject, POKEMON)
                val base = pokemonState.asJsonObject.get("baseMoveSlots")
                pokemon.baseMoveSlots = if (base == null || base.isJsonNull) {
                    pokemon.moveSlots.toMutableList()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val slots = (reader.value(base, MOVE_SLOTS, null) as List<MoveSlot>).toMutableList()
                    for ((k, slot) in slots.withIndex()) {
                        val current = pokemon.moveSlots.getOrNull(k)
                        if (current != null && current.id == slot.id && !current.virtual) slots[k] = current
                    }
                    slots
                }
            }
            sideState.getAsJsonObject("choice")?.let { choice ->
                reader.fill(side.choice, choice, CHOICE)
                side.choice.switchIns.clear()
                choice.getAsJsonArray("switchIns")?.forEach { side.choice.switchIns.add(it.asInt) }
            }
        }
        val requests = if (battle.requestState.isEmpty()) null else battle.getRequests(battle.requestState)
        for ((i, sideState) in sideStates.withIndex()) {
            val explicitNull = sideState.has("activeRequest") && sideState.get("activeRequest").isJsonNull
            battle.sides[i].activeRequest = if (explicitNull) null else requests?.get(i)
        }
        battle.prng = Prng(ints(state.getAsJsonArray("prng")))
        battle.queue.list = state.getAsJsonArray("queue")?.map { reader.action(it.asJsonObject) }?.toMutableList() ?: ArrayList()
        if (keepLog) {
            // Lines are edited in place by index (`lastMoveLine`), so the log carries over whole.
            battle.log.clear()
            state.getAsJsonArray("log")?.forEach { battle.log.add(it.asString) }
        }
        return Result(battle, reader.unknown)
    }

    /**
     * The same singles or doubles battle seen from the other seat: the sides trade places, and every reference to one
     * (`[Side:p1]`, `[Pokemon:p2a]`, a slot such as `p1b`, a side ID) now names the other. Returns a copy.
     */
    fun swapSides(state: JsonObject): JsonObject {
        val swapped = swapStrings(state).asJsonObject
        val sides = swapped.getAsJsonArray("sides")
        require(sides.size() == 2) { "Only two-sided battles change seats" }
        val reordered = JsonArray().apply { add(sides[1]); add(sides[0]) }
        reordered.forEachIndexed { n, side -> side.asJsonObject.addProperty("n", n) }
        swapped.add("sides", reordered)
        return swapped
    }

    private val SIDE_TEXT = Regex("""^p([12])([a-x]?)$""")
    private val SIDE_REF = Regex("""\[(Side|Pokemon):p([12])""")

    private fun swapStrings(json: JsonElement): JsonElement = when {
        json.isJsonObject -> JsonObject().also { out -> json.asJsonObject.entrySet().forEach { (k, v) -> out.add(k, swapStrings(v)) } }
        json.isJsonArray -> JsonArray().also { out -> json.asJsonArray.forEach { out.add(swapStrings(it)) } }
        json.isJsonPrimitive && json.asJsonPrimitive.isString -> {
            val text = json.asString
            val side = SIDE_TEXT.matchEntire(text)
            when {
                side != null -> JsonPrimitive("p" + other(side.groupValues[1]) + side.groupValues[2])
                text.startsWith("[") -> JsonPrimitive(SIDE_REF.replace(text) { m -> "[${m.groupValues[1]}:p${other(m.groupValues[2])}" })
                else -> json
            }
        }
        else -> json
    }

    private fun other(digit: String) = if (digit == "1") "2" else "1"

    /** `side.team`: for each original team index, the Pokemon's 1-based position in `side.pokemon`. */
    private fun teamOrder(sideState: JsonObject, size: Int): List<Int> {
        val team = sideState.get("team").asString
        return (if (size > 9) team.split(",") else team.map { it.toString() }).map { it.toInt() }
    }

    private fun set(raw: JsonObject): PokemonSet {
        fun string(key: String) = raw.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        fun int(key: String) = raw.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        fun stats(key: String): MutableMap<String, Int> = raw.getAsJsonObject(key)?.entrySet()
            ?.associateTo(linkedMapOf()) { it.key to it.value.asInt } ?: linkedMapOf()
        return PokemonSet(
            species = string("species") ?: "",
            name = string("name") ?: "",
            level = int("level") ?: 100,
            gender = string("gender") ?: "",
            ability = string("ability") ?: "",
            item = string("item") ?: "",
            nature = string("nature") ?: "",
            evs = stats("evs"),
            ivs = stats("ivs"),
            moves = raw.getAsJsonArray("moves")?.map { it.asString } ?: emptyList(),
            movesInfo = raw.getAsJsonArray("movesInfo")?.map { info ->
                val o = info.asJsonObject
                intArrayOf(o.get("pp").asInt, (o.get("maxPp") ?: o.get("maxpp")).asInt)
            },
            teraType = string("teraType"),
            uuid = string("uuid") ?: "",
            shiny = raw.get("shiny")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            happiness = int("happiness"),
            dynamaxLevel = int("dynamaxLevel"),
            gigantamax = raw.get("gigantamax")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            hpType = string("hpType"),
            currentHealth = int("currentHealth"),
            status = string("status"),
            statusDuration = int("statusDuration"),
            pokeball = string("pokeball"),
        )
    }

    private fun ints(array: JsonArray): IntArray = IntArray(array.size()) { array[it].asInt }

    private class Reader(val battle: Battle) {
        val dex: EngineDex = battle.dex
        val unknown = linkedSetOf<String>()

        /** Writes each serialized property onto the field of the same name, as `State.deserialize` does. */
        fun fill(target: Any, state: JsonObject, skip: Set<String>) {
            val fields = fieldsOf(target.javaClass)
            for ((key, json) in state.entrySet()) {
                if (key in skip) continue
                val field = fields[key]
                if (field == null) {
                    unknown += "${target.javaClass.simpleName}.$key"
                    continue
                }
                val current = field.get(target)
                // Only a read-only field's collection is the object's own: a mutable one may be shared with the dex
                // (a species' types) or with another battle object, and is replaced instead.
                val converted = try {
                    value(json, field.genericType, current.takeIf { Modifier.isFinal(field.modifiers) })
                } catch (e: UnsupportedValue) {
                    unknown += "${target.javaClass.simpleName}.$key:${e.message}"
                    continue
                }
                if (converted !== current) field.set(target, converted)
            }
        }

        /**
         * [json] as a value of [type]. A collection already on the object is refilled in place and returned as is, so
         * read-only fields keep their identity.
         */
        fun value(json: JsonElement, type: Type, current: Any?): Any? {
            if (json.isJsonNull) return null
            val raw = rawClass(type)
            return when {
                raw == Int::class.javaPrimitiveType || raw == Integer::class.java -> number(json).toInt()
                raw == Double::class.javaPrimitiveType || raw == java.lang.Double::class.java -> number(json).toDouble()
                raw == Long::class.javaPrimitiveType || raw == java.lang.Long::class.java -> number(json).toLong()
                raw == Boolean::class.javaPrimitiveType || raw == java.lang.Boolean::class.java -> json.asBoolean
                raw == String::class.java -> if (json.isJsonPrimitive) json.asString else throw UnsupportedValue("string")
                raw == IntArray::class.java -> json.asJsonArray.let { a -> IntArray(a.size()) { a[it].asInt } }
                raw == Species::class.java -> species(json)
                raw == Pokemon::class.java -> ref(json) as? Pokemon ?: throw UnsupportedValue("pokemon")
                raw == Side::class.java -> ref(json) as? Side ?: throw UnsupportedValue("side")
                raw == EffectState::class.java -> effectState(json.asJsonObject)
                raw == ActiveMove::class.java -> activeMove(json.asJsonObject)
                raw == MoveSlot::class.java -> moveSlot(json.asJsonObject)
                raw == Pokemon.Attacker::class.java -> attacker(json.asJsonObject)
                raw == Action::class.java -> action(json.asJsonObject)
                raw == Battle.FaintData::class.java -> json.asJsonObject.let { o ->
                    Battle.FaintData(ref(o.get("target")) as Pokemon, o.get("source")?.let { ref(it) as? Pokemon },
                        o.get("effect")?.let { effect(it) })
                }
                raw == HitData::class.java && json.isJsonObject && !isActiveMove(json.asJsonObject) -> hit(json.asJsonObject)
                EffectLike::class.java.isAssignableFrom(raw) -> effect(json)
                Map::class.java.isAssignableFrom(raw) -> map(json.asJsonObject, typeArgument(type, 1), current)
                Collection::class.java.isAssignableFrom(raw) -> collection(json.asJsonArray, raw, typeArgument(type, 0), current)
                raw == Any::class.java -> generic(json)
                else -> {
                    val instance = newInstance(raw) ?: throw UnsupportedValue(raw.simpleName)
                    fill(instance, json.asJsonObject, emptySet())
                    instance
                }
            }
        }

        private fun map(json: JsonObject, valueType: Type, current: Any?): Any {
            @Suppress("UNCHECKED_CAST")
            val target = (current as? MutableMap<String, Any?>)?.also { it.clear() } ?: LinkedHashMap()
            for ((key, element) in json.entrySet()) target[key] = value(element, valueType, null)
            return target
        }

        private fun collection(json: JsonArray, raw: Class<*>, elementType: Type, current: Any?): Any {
            val values = json.map { value(it, elementType, null) }
            @Suppress("UNCHECKED_CAST")
            (current as? MutableCollection<Any?>)?.let { existing ->
                existing.clear()
                existing.addAll(values)
                return existing
            }
            return if (Set::class.java.isAssignableFrom(raw)) LinkedHashSet(values) else ArrayList(values)
        }

        /** A value whose engine field is untyped: refs resolved, whole numbers as Int, objects as maps. */
        fun generic(json: JsonElement): Any? = when {
            json.isJsonNull -> null
            json.isJsonPrimitive -> json.asJsonPrimitive.let { p ->
                when {
                    p.isBoolean -> p.asBoolean
                    p.isNumber -> p.asDouble.let { d -> if (d == Math.rint(d) && Math.abs(d) < Int.MAX_VALUE) d.toInt() else d }
                    else -> ref(p) ?: p.asString
                }
            }
            json.isJsonArray -> json.asJsonArray.mapTo(ArrayList()) { generic(it) }
            isActiveMove(json.asJsonObject) -> activeMove(json.asJsonObject)
            isMoveLiteral(json.asJsonObject) -> moveLiteral(json.asJsonObject)
            else -> json.asJsonObject.entrySet().associateTo(LinkedHashMap()) { it.key to generic(it.value) }
        }

        private fun number(json: JsonElement): Number = when {
            json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> json.asNumber
            json.isJsonPrimitive && json.asJsonPrimitive.isBoolean -> if (json.asBoolean) 1 else 0
            else -> throw UnsupportedValue("number")
        }

        /** `State.fromRef`: `[Pokemon:p1a]` is the side's Pokemon at position a; effects come from the dex. */
        fun ref(json: JsonElement): Any? {
            if (!json.isJsonPrimitive || !json.asJsonPrimitive.isString) return null
            val text = json.asString
            if (!text.startsWith("[") || !text.endsWith("]")) return null
            val inner = text.substring(1, text.length - 1)
            if (inner == "Battle") return battle
            if (inner == "Field") return battle.field
            val type = inner.substringBefore(':')
            val id = inner.substringAfter(':', "")
            return when (type) {
                "Side" -> battle.sides[id[1] - '1']
                "Pokemon" -> battle.sides[id[1] - '1'].pokemon[POSITIONS.indexOf(id[2])]
                "Ability" -> dex.ability(id)
                "Item" -> dex.item(id)
                "Move" -> dex.moveOrPlaceholder(id)
                "Condition" -> dex.conditionById(id)
                "Species" -> dex.species(id)
                else -> null
            }
        }

        private fun species(json: JsonElement): Species =
            (ref(json) as? Species) ?: dex.species(json.asString) ?: throw UnsupportedValue("species")

        private fun effect(json: JsonElement): EffectLike? {
            if (json.isJsonObject) {
                val o = json.asJsonObject
                if (isActiveMove(o)) return activeMove(o)
                val id = o.get("id")?.asString ?: return Battle.EMPTY_EFFECT
                return if (id.isEmpty()) Battle.EMPTY_EFFECT else dex.conditionById(id)
            }
            return ref(json) as? EffectLike ?: if (json.asString.isEmpty()) Battle.EMPTY_EFFECT else dex.conditionById(json.asString)
        }

        /** An effect's state: its own fields, and everything else in [EffectState.values] as Showdown keeps it. */
        fun effectState(o: JsonObject): EffectState {
            val state = EffectState(o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
            for ((key, json) in o.entrySet()) {
                when (key) {
                    "id" -> Unit
                    "duration" -> state.duration = if (json.isJsonNull) null else number(json).toInt()
                    "target" -> state.target = generic(json)
                    "source" -> state.source = ref(json) as? Pokemon
                    "sourceSlot" -> state.sourceSlot = if (json.isJsonNull) null else json.asString
                    "sourceEffect" -> state.sourceEffect = if (json.isJsonNull) null else effect(json)
                    else -> state[key] = generic(json)
                }
            }
            return state
        }

        fun activeMove(o: JsonObject): ActiveMove {
            val id = (ref(o.get("move") ?: o.get("id")) as? EffectLike)?.id ?: o.get("id").asString
            val move = dex.activeMove(id)
            val fields = fieldsOf(ActiveMove::class.java)
            for ((key, json) in o.entrySet()) {
                if (key in ACTIVE_MOVE) continue
                val field = fields[key]
                if (field == null) {
                    move.extra[key] = generic(json)
                    continue
                }
                val current = field.get(move)
                val converted = try { value(json, field.genericType, current.takeIf { Modifier.isFinal(field.modifiers) }) } catch (e: UnsupportedValue) {
                    unknown += "ActiveMove.$key:${e.message}"
                    continue
                }
                if (converted !== current) field.set(move, converted)
            }
            return move
        }

        private fun moveSlot(o: JsonObject): MoveSlot = MoveSlot(
            move = o.get("move").asString,
            id = o.get("id").asString,
            pp = o.get("pp").asInt,
            maxpp = o.get("maxpp").asInt,
            target = o.get("target")?.asString ?: "",
            disabled = o.get("disabled")?.let { generic(it) } ?: false,
            disabledSource = o.get("disabledSource")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
            used = o.get("used")?.asBoolean ?: false,
            virtual = o.get("virtual")?.asBoolean ?: false,
        )

        private fun attacker(o: JsonObject): Pokemon.Attacker = Pokemon.Attacker(
            source = ref(o.get("source")) as Pokemon,
            damage = o.get("damage")?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt else 0 } ?: 0,
            move = o.get("move").asString,
            thisTurn = o.get("thisTurn")?.asBoolean ?: false,
            slot = o.get("slot").asString,
            damageValue = o.get("damageValue")?.let { generic(it) },
        )

        fun action(o: JsonObject): Action {
            val action = Action(o.get("choice").asString)
            fill(action, o, setOf("choice"))
            return action
        }

        private fun isActiveMove(o: JsonObject) = o.has("hit") && (o.has("id") || o.has("move"))

        /**
         * A move written out as data, like Future Sight's `moveData`: Showdown hits with the plain object, the engine
         * with an [ActiveMove] built on that data alone, without the named move's handlers.
         */
        private fun isMoveLiteral(o: JsonObject) = o.get("effectType")?.takeIf { it.isJsonPrimitive }?.asString == "Move" && o.has("id")

        private fun moveLiteral(o: JsonObject): ActiveMove =
            ActiveMove(jbro.cobblemon.mcc.betterai.engine.dex.MoveData(o.get("id").asString, o, ""))

        /** A secondary or self block a handler built, like Curse's `move.self = {boosts}`: data only, no handlers. */
        private fun hit(o: JsonObject): HitData = LiteralHit(
            hitBoosts = o.getAsJsonObject("boosts")?.entrySet()?.associate { it.key to it.value.asInt },
            chance = o.get("chance")?.takeIf { it.isJsonPrimitive }?.asInt,
            hitStatus = o.get("status")?.takeIf { it.isJsonPrimitive }?.asString,
            hitVolatileStatus = o.get("volatileStatus")?.takeIf { it.isJsonPrimitive }?.asString,
            hitSelf = o.getAsJsonObject("self")?.let { hit(it) },
        )
    }

    private class UnsupportedValue(message: String) : RuntimeException(message, null, false, false)

    private val fieldCache = object : ClassValue<Map<String, Field>>() {
        override fun computeValue(type: Class<*>): Map<String, Field> {
            val fields = LinkedHashMap<String, Field>()
            var c: Class<*>? = type
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.isSynthetic || f.name in fields) continue
                    f.isAccessible = true
                    fields[f.name] = f
                }
                c = c.superclass
            }
            return fields
        }
    }

    private fun fieldsOf(type: Class<*>): Map<String, Field> = fieldCache.get(type)

    private fun newInstance(type: Class<*>): Any? = try {
        type.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
    } catch (_: ReflectiveOperationException) {
        null
    }

    private fun rawClass(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as Class<*>
        is WildcardType -> rawClass(type.upperBounds.first())
        else -> Any::class.java
    }

    private fun typeArgument(type: Type, index: Int): Type {
        val parameterized = type as? ParameterizedType ?: return Any::class.java
        val argument = parameterized.actualTypeArguments.getOrNull(index) ?: return Any::class.java
        return if (argument is WildcardType) argument.upperBounds.first() else argument
    }

    private const val POSITIONS = "abcdefghijklmnopqrstuvwx"

    private val MOVE_SLOTS: Type = object : ParameterizedType {
        override fun getRawType(): Type = List::class.java
        override fun getOwnerType(): Type? = null
        override fun getActualTypeArguments(): Array<Type> = arrayOf(MoveSlot::class.java)
    }

    /**
     * Showdown's own skip lists, plus the battle's event bookkeeping: the serialized battle is between choices, where
     * the engine's event fields are at rest, and the log is not kept for search.
     */
    private val BATTLE = setOf(
        "dex", "gen", "ruleTable", "id", "log", "inherit", "format", "teamGenerator", "HIT_SUBSTITUTE", "NOT_FAIL",
        "FAIL", "SILENT_FAIL", "field", "sides", "prng", "hints", "deserialized", "queue", "actions",
        "effect", "effectState", "event", "events", "eventDepth", "inputLog", "messageLog", "sentLogPos", "sentEnd",
        "formatData", "formatid", "prngSeed", "gameType", "activePerHalf", "strictChoices", "debugMode",
        "forceRandomChance", "rated", "reportExactHP", "reportPercentages", "supportCancel", "send",
    )
    private val FIELD = setOf("id", "battle")
    private val SIDE = setOf("battle", "team", "pokemon", "choice", "activeRequest", "name", "n", "id")
    private val POKEMON = setOf("side", "battle", "set", "name", "fullname", "id", "happiness", "level", "pokeball",
        "baseMoveSlots")
    private val CHOICE = setOf("switchIns")
    private val ACTIVE_MOVE = setOf("move", "id")
}
