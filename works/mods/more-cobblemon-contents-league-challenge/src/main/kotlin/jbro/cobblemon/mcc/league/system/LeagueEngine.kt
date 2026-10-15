package jbro.cobblemon.mcc.league.system

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
    /** The trainer's lines by moment (`battle_start`, `last_pokemon`, `player_won`, `player_lost`), as translation keys. */
    val scenes: Map<String, List<String>> = emptyMap(),
    val battleCap: Int = 0,
    val type: String? = null,
    /** Pinned presentation role so a reload cannot reclassify a running encounter. */
    val stage: String? = null,
)

data class LeagueCatalog(
    val id: String,
    val nameKey: String,
    val initialCap: Int,
    val gyms: List<String>,
    val finals: List<String>,
    val challenges: Map<String, Challenge>,
    val wildLevel: WildLevelRule = WildLevelRule(),
    /** The regional hard route, opened by its normal Champion; empty when the league has none. */
    val hardGyms: List<String> = emptyList(),
    val hardFinals: List<String> = emptyList(),
    val gymStages: List<List<String>> = gyms.map { listOf(it) },
    val hardGymStages: List<List<String>> = hardGyms.map { listOf(it) },
    val finalChoices: Map<String, List<String>> = emptyMap(),
    val hardFinalChoices: Map<String, List<String>> = emptyMap(),
    val unlockLeague: String? = null,
    val playerMechanic: String = "MEGA",
    val lockMechanicsUntilChampion: Boolean = true,
) {
    val hasHard: Boolean get() = hardGyms.isNotEmpty()

    fun gymStages(hard: Boolean): List<List<String>> = if (hard) hardGymStages else gymStages
    fun finals(hard: Boolean): List<String> = if (hard) hardFinals else finals

    /** Whether [challengeId] belongs to the hard route. */
    fun isHard(challengeId: String): Boolean = challengeId in hardGyms || challengeId in hardFinals ||
        challengeId in hardFinalChoices.values.flatten()

    init {
        requireId(id)
        require(initialCap in 1..100)
        require(gymStages.size == 8 && gymStages.flatten() == gyms && gymStages.all { it.size in 1..2 })
        require(finals.size in 1..16)
        require((hardGyms.isEmpty() && hardFinals.isEmpty()) ||
            (hardGymStages.size == 8 && hardGymStages.flatten() == hardGyms && hardGymStages.all { it.size in 1..2 } && hardFinals.size in 1..16))
        val alternatives = (finalChoices.values + hardFinalChoices.values).flatten()
        val route = gyms + finals + hardGyms + hardFinals
        require(route.distinct().size == route.size)
        require((route + alternatives).all { it in challenges })
        listOf(finals to finalChoices, hardFinals to hardFinalChoices).forEach { (sequence, choices) ->
            require(choices.keys.all { it in sequence && it != sequence.first() && it != sequence.last() })
            require(choices.all { (id, options) -> id in options && options.size in 1..2 && options.distinct().size == options.size && options.none { it in sequence && it != id } })
            require(choices.values.flatten().filter { it !in sequence }.distinct().size == choices.values.flatten().count { it !in sequence })
        }
        require(playerMechanic in setOf("NONE", "MEGA", "DYNAMAX", "TERA"))
        unlockLeague?.let(::requireId)
        require(gyms.all { challenges.getValue(it).badge != null })
        require(gyms.map { challenges.getValue(it).badge }.distinct().size == gyms.size)
        // Badges come from the normal gyms; the hard route only replays them.
        require((finals + hardGyms + hardFinals + alternatives).all { challenges.getValue(it).badge == null })
        val caps = listOf(initialCap) + route.map { challenges.getValue(it).unlockCap }
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
            require(challenge.battleCap in 0..100)
            require(challenge.type == null || challenge.type in POKEMON_TYPES)
            if (challenge.format == "DOUBLE") require(challenge.team.size >= 2)
            challenge.badge?.let(::requireId)
            challenge.skin?.let { requireId(it); require(it.endsWith(".png") && !it.contains("..")) }
        }
    }
}

internal val POKEMON_TYPES = setOf("normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
    "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy")

internal fun requireId(value: String) {
    require(!value.contains("://") && !value.contains("..") && !value.substringAfter(':').startsWith('/')) { "Only local resource IDs are accepted" }
    require(value.length <= 256 && value.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid namespaced ID: $value" }
}

/** Serializable outbox. BP and badge delivery are independently idempotent. */
data class LeagueReward(val token: UUID, val challengeId: String, val badge: String?, val bp: Long,
    val badgeDone: Boolean = badge == null, val bpDone: Boolean = bp == 0L)

/** Encounters and serialized Pokemon are pinned for the whole run, including across /reload. */
data class LeagueRun(val token: UUID, val encounters: List<Challenge>, val party: List<String>,
    val index: Int = 0, val battleToken: UUID = UUID.randomUUID(), val awaitingNext: Boolean = false,
    val battleCap: Int? = null, val mechanic: String? = null, val hard: Boolean? = null) {
    val challengeId: String get() = encounters[index].id
}

data class LeagueProgress(
    val revision: Long = 0,
    val cleared: Set<String> = emptySet(),
    val champion: Boolean = false,
    val championAt: Long? = null,
    /** The hard route's Champion was beaten too. */
    val hardChampion: Boolean = false,
    val unlockedCap: Int = 0,
    val run: LeagueRun? = null,
    val rewards: List<LeagueReward> = emptyList(),
)

/** Pure transitions. The server must durably commit the result before triggering side effects. */
class LeagueEngine(private val catalog: LeagueCatalog) {
    companion object { const val MAX_REWARDS = 4096 }
    fun badgeCount(state: LeagueProgress): Int = catalog.gymStages.count { stage -> stage.any { it in state.cleared } }

    /** The normal Champion opens the hard route. */
    fun hardUnlocked(state: LeagueProgress): Boolean = catalog.hasHard && state.champion
    /**
     * The cap the cleared challenges earn under the current catalog. It is derived rather than read from the stored
     * [LeagueProgress.unlockedCap], so a data pack that moves the caps also moves the caps of progress saved before.
     */
    fun cap(state: LeagueProgress): Int =
        (state.cleared.mapNotNull { catalog.challenges[it]?.unlockCap } + catalog.initialCap).max()

    fun available(state: LeagueProgress, challengeId: String): Boolean {
        val hard = catalog.isHard(challengeId)
        if (hard && !hardUnlocked(state)) return false
        val stages = catalog.gymStages(hard)
        val finals = catalog.finals(hard)
        val index = stages.indexOfFirst { challengeId in it }
        return if (index >= 0) stages.take(index).all { stage -> stage.any { it in state.cleared } }
        else challengeId == finals.firstOrNull() && stages.all { stage -> stage.any { it in state.cleared } }
    }

    /** The encounter cap is independent of the player's current progress, including on rematches. */
    fun battleCap(challengeId: String): Int {
        val challenge = catalog.challenges.getValue(challengeId)
        if (challenge.battleCap > 0) return challenge.battleCap
        val hard = catalog.isHard(challengeId)
        val stages = catalog.gymStages(hard)
        val index = stages.indexOfFirst { challengeId in it }
        return if (hard) { if (index >= 0) 80 else 100 }
        else if (index == 0) catalog.initialCap
        else catalog.challenges.getValue(stages[if (index < 0) stages.lastIndex else index - 1].first()).unlockCap
    }

    fun mechanic(state: LeagueProgress): String = if (catalog.lockMechanicsUntilChampion && !state.champion) "NONE" else catalog.playerMechanic

    fun begin(state: LeagueProgress, challengeId: String, party: List<String>, finalChoiceId: String? = null): LeagueProgress {
        require(state.run == null) { "run_active" }
        require(party.size in 1..6 && party.all { it.isNotBlank() }) { "party_required" }
        require(state.rewards.all { it.badgeDone && it.bpDone }) { "rewards_pending" }
        val hard = catalog.isHard(challengeId)
        if (hard) require(hardUnlocked(state)) { "hard_locked" }
        val finals = catalog.finals(hard)
        val stages = catalog.gymStages(hard)
        // A gym is one battle; the finals are one run.
        val encounters = if (stages.any { challengeId in it }) {
            require(finalChoiceId == null) { "invalid_branch" }
            require(available(state, challengeId)) { "prerequisite" }
            listOf(catalog.challenges.getValue(challengeId))
        } else {
            require(challengeId == finals.first() && stages.all { stage -> stage.any { it in state.cleared } }) { "prerequisite" }
            val choices = if (hard) catalog.hardFinalChoices else catalog.finalChoices
            require(finalChoiceId == null || choices.values.any { finalChoiceId in it }) { "invalid_branch" }
            finals.map { id -> catalog.challenges.getValue(if (finalChoiceId != null && finalChoiceId in choices[id].orEmpty()) finalChoiceId else id) }
        }
        // Reserve the entire run before battle, so a later victory cannot exceed storage capacity.
        require(state.rewards.size + encounters.size <= MAX_REWARDS) { "history_full" }
        return state.copy(revision = state.revision + 1, run = LeagueRun(UUID.randomUUID(),
            encounters.map { it.copy(team = it.team.toList(), stage = stage(it.id)) }, party.toList(),
            battleCap = battleCap(challengeId), mechanic = mechanic(state), hard = hard))
    }

    /**
     * Where [run]'s current battle stands: `gym`, `elite_four`, `tournament` or `champion`, prefixed `hard_` on
     * the hard route.
     */
    fun stage(run: LeagueRun): String {
        run.encounters[run.index].stage?.let { return it }
        val id = run.challengeId
        val role = when {
            id in catalog.gyms || id in catalog.hardGyms -> "gym"
            run.index == run.encounters.lastIndex -> "champion"
            else -> "elite_four"
        }
        return if (catalog.isHard(id)) "hard_$role" else role
    }

    /** Where [challengeId] stands on its own, as [stage] names a run's battle: a Champion is the last of its finals. */
    fun stage(challengeId: String): String {
        val role = when (challengeId) {
            in catalog.gyms, in catalog.hardGyms -> "gym"
            catalog.finals.last(), catalog.hardFinals.lastOrNull() -> "champion"
            else -> if (catalog.unlockLeague != null) "tournament" else "elite_four"
        }
        return if (catalog.isHard(challengeId)) "hard_$role" else role
    }

    fun next(state: LeagueProgress): LeagueProgress {
        val run = requireNotNull(state.run) { "no_run" }
        require(run.awaitingNext && run.index + 1 < run.encounters.size) { "phase_invalid" }
        require(state.rewards.all { it.badgeDone && it.bpDone }) { "rewards_pending" }
        return state.copy(revision = state.revision + 1,
            run = run.copy(index = run.index + 1, battleToken = UUID.randomUUID(), awaitingNext = false))
    }

    /** Every challenge in the order it is played: normal gyms, normal finals, hard gyms, hard finals. */
    fun route(): List<String> = catalog.gyms + catalog.finals + catalog.hardGyms + catalog.hardFinals

    /**
     * Progress as an operator sets it: every challenge on the [route] before [challengeId] beaten and none from it on,
     * or the whole route when [challengeId] is null. Titles follow the beaten routes; no BP or reward is added. The
     * Elite Four and the Champion are one run, so a player set just before a Champion still starts from the first
     * of the Elite Four.
     */
    fun clearedBefore(state: LeagueProgress, challengeId: String?, now: Long): LeagueProgress {
        require(state.run == null) { "run_active" }
        val route = route()
        val count = if (challengeId == null) route.size else route.indexOf(challengeId).also { require(it >= 0) { "unknown_challenge" } }
        // A stage of two gyms to pick from is passed by beating one: before [challengeId] only its first counts as
        // beaten, as a player who went that way would have it; the whole route (null) beats both.
        val passedBy = if (challengeId == null) emptySet() else (catalog.gymStages + catalog.hardGymStages)
            .filter { it.size > 1 && it.all { gym -> route.indexOf(gym) < count } }.flatMap { it.drop(1) }.toSet()
        val cleared = route.take(count).toSet() - passedBy
        val champion = catalog.finals.last() in cleared
        return state.copy(revision = state.revision + 1, cleared = cleared, champion = champion,
            championAt = if (champion) state.championAt ?: now else null,
            hardChampion = catalog.hasHard && catalog.hardFinals.last() in cleared,
            unlockedCap = (cleared.map { catalog.challenges.getValue(it).unlockCap } + catalog.initialCap).max())
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
        val hardRun = run.hard ?: catalog.isHard(run.encounters.first().id)
        val finalWin = run.index == run.encounters.lastIndex &&
            (challenge.stage?.endsWith("champion") ?: (challenge.id ==
                (if (hardRun) catalog.hardFinals.lastOrNull() else catalog.finals.last())))
        val champion = state.champion || (finalWin && !hardRun)
        val hardChampion = state.hardChampion || (finalWin && hardRun)
        val reward = LeagueReward(battleToken, challenge.id, challenge.badge,
            if (first) challenge.firstBp else challenge.repeatBp)
        return state.copy(revision = state.revision + 1, cleared = state.cleared + challenge.id,
            champion = champion, championAt = state.championAt ?: now.takeIf { champion }, hardChampion = hardChampion,
            unlockedCap = maxOf(state.unlockedCap, challenge.unlockCap),
            run = if (run.index == run.encounters.lastIndex) null else run.copy(awaitingNext = true),
            // Keep paid BP receipts: reconciliation can repair a lagging BP save idempotently.
            rewards = state.rewards.filter { it.bp > 0 || !it.badgeDone || !it.bpDone } +
                if (first || reward.bp > 0) listOf(reward) else emptyList())
    }
}
