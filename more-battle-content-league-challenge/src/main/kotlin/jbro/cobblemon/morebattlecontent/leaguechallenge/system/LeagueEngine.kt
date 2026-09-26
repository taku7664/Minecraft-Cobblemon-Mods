package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import java.util.UUID

data class Challenge(
    val id: String,
    val nameKey: String,
    val team: List<String>,
    val badge: String?,
    val unlockCap: Int,
    val firstBp: Long,
    val repeatBp: Long,
    val mechanic: String,
    val format: String,
    val skin: String?,
    val slim: Boolean,
    val skill: Int = 3,
)

data class LeagueCatalog(
    val id: String,
    val nameKey: String,
    val initialCap: Int,
    val gyms: List<String>,
    val finals: List<String>,
    val challenges: Map<String, Challenge>,
) {
    init {
        requireId(id)
        require(initialCap in 1..100)
        require(gyms.size == 8 && finals.size == 5)
        require((gyms + finals).distinct().size == 13)
        require((gyms + finals).all { it in challenges })
        require(gyms.all { challenges.getValue(it).badge != null })
        require(gyms.map { challenges.getValue(it).badge }.distinct().size == 8)
        require(finals.all { challenges.getValue(it).badge == null })
        val caps = listOf(initialCap) + (gyms + finals).map { challenges.getValue(it).unlockCap }
        require(caps.zipWithNext().all { (a, b) -> a <= b }) { "Level caps must never decrease" }
        challenges.forEach { (id, challenge) ->
            requireId(id)
            require(id == challenge.id)
            require(challenge.nameKey.isNotBlank() && challenge.nameKey.length <= 256)
            require(challenge.team.size in 1..6)
            require(challenge.team.all { it.isNotBlank() && it.length <= 2048 })
            require(challenge.unlockCap in 1..100)
            require(challenge.firstBp in 0..1_000_000 && challenge.repeatBp in 0..1_000_000)
            require(challenge.format in setOf("SINGLE", "DOUBLE"))
            require(challenge.skill in 0..5)
            require(challenge.mechanic in setOf("NONE", "MEGA", "DYNAMAX", "TERA"))
            if (challenge.format == "DOUBLE") require(challenge.team.size >= 2)
            challenge.badge?.let(::requireId)
            challenge.skin?.let { requireId(it); require(it.endsWith(".png") && !it.contains("..")) }
        }
    }
}

internal fun requireId(value: String) {
    require(!value.contains("://") && !value.contains("..") && !value.substringAfter(':').startsWith('/')) { "Only local resource IDs are accepted" }
    require(value.length <= 256 && value.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid namespaced ID: $value" }
}

/** Serializable outbox. BP and badge delivery are independently idempotent. */
data class LeagueReward(val token: UUID, val challengeId: String, val badge: String?, val bp: Long,
    val badgeDone: Boolean = badge == null, val bpDone: Boolean = bp == 0L)

/** Encounters and serialized Pokemon are pinned for the whole run, including across /reload. */
data class LeagueRun(val token: UUID, val encounters: List<Challenge>, val party: List<String>,
    val index: Int = 0, val battleToken: UUID = UUID.randomUUID(), val awaitingNext: Boolean = false) {
    val challengeId: String get() = encounters[index].id
}

data class LeagueProgress(
    val revision: Long = 0,
    val cleared: Set<String> = emptySet(),
    val champion: Boolean = false,
    val championAt: Long? = null,
    val unlockedCap: Int = 0,
    val run: LeagueRun? = null,
    val rewards: List<LeagueReward> = emptyList(),
)

/** Pure transitions. The server must durably commit the result before triggering side effects. */
class LeagueEngine(private val catalog: LeagueCatalog) {
    companion object { const val MAX_REWARDS = 4096 }
    fun badgeCount(state: LeagueProgress): Int = catalog.gyms.count { it in state.cleared }
    fun cap(state: LeagueProgress): Int = maxOf(catalog.initialCap, state.unlockedCap)

    fun begin(state: LeagueProgress, challengeId: String, party: List<String>): LeagueProgress {
        require(state.run == null) { "run_active" }
        require(party.size == 6 && party.all { it.isNotBlank() }) { "party_required" }
        require(state.rewards.all { it.badgeDone && it.bpDone }) { "rewards_pending" }
        val gymIndex = catalog.gyms.indexOf(challengeId)
        val encounters = if (gymIndex >= 0) {
            require(catalog.gyms.take(gymIndex).all { it in state.cleared }) { "prerequisite" }
            listOf(catalog.challenges.getValue(challengeId))
        } else {
            require(challengeId == catalog.finals.first() && badgeCount(state) == 8) { "prerequisite" }
            catalog.finals.map { catalog.challenges.getValue(it) }
        }
        // Reserve the entire run before battle, so a later victory cannot exceed storage capacity.
        require(state.rewards.size + encounters.size <= MAX_REWARDS) { "history_full" }
        return state.copy(revision = state.revision + 1, run = LeagueRun(UUID.randomUUID(),
            encounters.map { it.copy(team = it.team.toList()) }, party.toList()))
    }

    fun next(state: LeagueProgress): LeagueProgress {
        val run = requireNotNull(state.run) { "no_run" }
        require(run.awaitingNext && run.index + 1 < run.encounters.size) { "phase_invalid" }
        require(state.rewards.all { it.badgeDone && it.bpDone }) { "rewards_pending" }
        return state.copy(revision = state.revision + 1,
            run = run.copy(index = run.index + 1, battleToken = UUID.randomUUID(), awaitingNext = false))
    }

    fun cancel(state: LeagueProgress): LeagueProgress = if (state.run == null) state else
        state.copy(revision = state.revision + 1, run = null)

    fun finish(state: LeagueProgress, battleToken: UUID, won: Boolean, now: Long): LeagueProgress {
        val run = state.run ?: return state
        if (run.battleToken != battleToken || run.awaitingNext) return state
        if (!won) return cancel(state)
        require(now >= 0)
        val challenge = run.encounters[run.index]
        val first = challenge.id !in state.cleared
        val champion = state.champion || (run.encounters.size == 5 && run.index == 4)
        val reward = LeagueReward(battleToken, challenge.id, challenge.badge,
            if (first) challenge.firstBp else challenge.repeatBp)
        return state.copy(revision = state.revision + 1, cleared = state.cleared + challenge.id,
            champion = champion, championAt = state.championAt ?: now.takeIf { champion },
            unlockedCap = maxOf(state.unlockedCap, challenge.unlockCap),
            run = if (run.index == run.encounters.lastIndex) null else run.copy(awaitingNext = true),
            // Keep paid BP receipts: reconciliation can repair a lagging BP save idempotently.
            rewards = state.rewards.filter { it.bp > 0 || !it.badgeDone || !it.bpDone } +
                if (first || reward.bp > 0) listOf(reward) else emptyList())
    }
}
