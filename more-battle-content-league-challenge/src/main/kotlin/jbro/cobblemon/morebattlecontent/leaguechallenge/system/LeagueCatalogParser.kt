package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** IDs come from resource paths, never from user-supplied filesystem paths. */
object LeagueCatalogParser {
    val directories = listOf("leagues", "challenges", "trainers", "teams", "rewards", "appearances")

    fun parse(resources: Map<String, Map<String, String>>, activeLeague: String): LeagueCatalog {
        fun document(kind: String, id: String): JsonObject {
            requireId(id)
            val raw = requireNotNull(resources[kind]?.get(id)) { "Missing $kind/$id" }
            require(raw.length <= 262144) { "Resource too large: $kind/$id" }
            return JsonParser.parseString(raw).asJsonObject.also {
                require(it.number("schema_version") == 1L) { "Unsupported schema: $kind/$id" }
            }
        }
        val league = document("leagues", activeLeague)
        val gyms = league.strings("gyms")
        val finals = league.strings("finals")
        val challenges = (gyms + finals).associateWith { id ->
            val source = document("challenges", id)
            val trainer = document("trainers", source.string("trainer"))
            val team = document("teams", trainer.string("team"))
            val reward = document("rewards", source.string("reward"))
            val appearance = trainer.optionalString("appearance")?.let { document("appearances", it) }
            Challenge(id, trainer.string("name_key"), team.strings("pokemon"), source.optionalString("badge"),
                reward.number("unlock_cap").toInt(), reward.number("first_bp"), reward.number("repeat_bp"),
                source.string("mechanic"), source.string("format"), appearance?.optionalString("skin"),
                appearance?.string("model")?.also { require(it in setOf("default", "slim")) } == "slim",
                trainer.number("ai_skill").toInt())
        }
        return LeagueCatalog(league.string("progress_id"), league.string("name_key"), league.number("initial_cap").toInt(), gyms, finals, challenges)
    }
}

internal fun JsonObject.string(key: String): String = requireNotNull(get(key)) { "Missing $key" }.let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Expected string: $key" }
    it.asString.also { value -> require(value.length <= 2048) }
}
internal fun JsonObject.optionalString(key: String): String? = if (!has(key) || get(key).isJsonNull) null else string(key)
internal fun JsonObject.number(key: String): Long = requireNotNull(get(key)) { "Missing $key" }.let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Expected number: $key" }
    it.asBigDecimal.longValueExact().also { value -> require(value in 0..Int.MAX_VALUE.toLong()) }
}
internal fun JsonObject.strings(key: String): List<String> = requireNotNull(get(key)) { "Missing $key" }.asJsonArray.map {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
    it.asString.also { value -> require(value.length <= 2048) }
}.also { require(it.size <= 128) }
