package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object LeagueProgressCodec {
    private val gson = Gson()
    fun encode(state: LeagueProgress): String = gson.toJson(JsonObject().apply {
        addProperty("schema_version", 1)
        add("progress", gson.toJsonTree(state))
    })

    fun decode(raw: String): LeagueProgress {
        require(raw.length <= 8_388_608) { "Progress is oversized" }
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.number("schema_version") == 1L) { "Unsupported progress schema" }
        val objectData = root.getAsJsonObject("progress")
        // Gson bypasses constructors: require fields and validate the complete graph explicitly.
        for (key in listOf("revision", "cleared", "champion", "unlockedCap", "rewards")) require(objectData.has(key))
        val state = requireNotNull(gson.fromJson(objectData, LeagueProgress::class.java))
        require(state.revision >= 0 && state.unlockedCap in 0..100)
        require(state.champion == (state.championAt != null))
        require(state.championAt == null || state.championAt >= 0)
        require(state.cleared.size <= 4096)
        state.cleared.forEach(::requireId)
        require(state.rewards.size <= LeagueEngine.MAX_REWARDS && state.rewards.map { it.token }.distinct().size == state.rewards.size)
        state.rewards.forEach {
            requireNotNull(it.token)
            requireId(it.challengeId)
            it.badge?.let(::requireId)
            require(it.bp in 0..1_000_000)
            require(it.badge != null || it.badgeDone)
            require(it.bp > 0 || it.bpDone)
        }
        state.run?.let { run ->
            requireNotNull(run.token); requireNotNull(run.battleToken)
            require(run.encounters.size == 1 || run.encounters.size == 5)
            require(run.index in run.encounters.indices)
            require(!run.awaitingNext || run.index < run.encounters.lastIndex)
            require(run.party.size == 6 && run.party.all { it.isNotBlank() && it.length <= 524288 })
            run.encounters.forEach {
                requireId(it.id)
                require(it.team.size in 1..6 && it.team.all { member -> member.isNotBlank() && member.length <= 2048 })
                require(it.unlockCap in 1..100 && it.firstBp in 0..1_000_000 && it.repeatBp in 0..1_000_000)
                require(it.nameKey.isNotBlank())
                require(it.format in setOf("SINGLE", "DOUBLE"))
                require(it.skill in 0..5)
                require(it.mechanic in setOf("NONE", "MEGA", "DYNAMAX", "TERA"))
                it.badge?.let(::requireId); it.skin?.let(::requireId)
            }
        }
        return state
    }
}
