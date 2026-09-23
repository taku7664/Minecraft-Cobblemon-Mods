package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

internal object PvpTurnResponseCardinality {
    @JvmStatic
    fun accepts(activeChoices: Int, forcedSwitchChoices: Int, responseCount: Int): Boolean {
        require(activeChoices >= 0 && forcedSwitchChoices >= 0 && responseCount >= 0)
        return responseCount == maxOf(activeChoices, forcedSwitchChoices)
    }
}

internal class PvpTurnCapture internal constructor(
    internal val coordinatorIdentity: Any,
    internal val battleId: UUID,
    internal val requestIdentity: Any,
    internal val submission: PvpTurnSubmission,
    val timedOut: Boolean,
    val personalTimeExhausted: Boolean,
)

internal data class PvpTurnTimeout(
    internal val coordinatorIdentity: Any,
    val battleId: UUID,
    val playerId: UUID,
    val requestIdentity: Any,
    val personalTimeExhausted: Boolean,
)

/** Keeps Cobblemon request identity tracking separate from the reusable match clock. */
internal class PvpBattleTurnCoordinator(
    private val timerForBattle: (UUID) -> PvpMatchTimer?,
) {
    private val identity = Any()
    private val battles = LinkedHashMap<UUID, BattleTurnState>()

    @Synchronized
    fun observe(battleId: UUID, requestsByPlayer: Map<UUID, Any>) {
        val timer = timerForBattle(battleId)
        if (timer == null) {
            battles.remove(battleId)
            return
        }
        if (requestsByPlayer.isEmpty()) return
        val state = battles.getOrPut(battleId, ::BattleTurnState)
        var window = state.window
        if (window == null || window.isResolved()) {
            val fresh = requestsByPlayer.filter { (playerId, requestIdentity) ->
                window?.requestIdentities?.get(playerId) !== requestIdentity
            }
            if (fresh.isEmpty()) return
            val turnId = ++state.lastTurnId
            timer.beginTurn(turnId, fresh.keys)
            window = TurnWindow(turnId, LinkedHashMap(fresh))
            state.window = window
            return
        }
        requestsByPlayer.forEach { (playerId, requestIdentity) ->
            if (playerId !in window.requestIdentities) {
                timer.requireTurnChoice(window.turnId, playerId)
                window.requestIdentities[playerId] = requestIdentity
            }
        }
    }

    @Synchronized
    fun capture(battleId: UUID, playerId: UUID, requestIdentity: Any): PvpTurnCapture? {
        val timer = timerForBattle(battleId) ?: return null
        val window = battles[battleId]?.window ?: return null
        if (
            window.requestIdentities[playerId] !== requestIdentity ||
            playerId in window.resolvedPlayers ||
            playerId in window.pendingTimeouts
        ) return null
        val submission = timer.captureTurnSubmission(window.turnId, playerId)
        val timedOut = timer.submissionTimedOut(submission)
        val personalTimeExhausted = if (timedOut) {
            check(timer.submitTurn(submission) == PvpTimedSubmissionStatus.TIMED_OUT)
            val exhausted = timer.remainingPersonalTime(playerId) == 0L
            window.pendingTimeouts[playerId] = PvpTurnTimeout(
                identity,
                battleId,
                playerId,
                requestIdentity,
                exhausted,
            )
            exhausted
        } else {
            false
        }
        return PvpTurnCapture(
            identity,
            battleId,
            requestIdentity,
            submission,
            timedOut,
            personalTimeExhausted,
        )
    }

    @Synchronized
    fun accept(capture: PvpTurnCapture): PvpTimedSubmissionStatus {
        require(capture.coordinatorIdentity === identity) { "PvP turn capture belongs to another coordinator" }
        val timer = timerForBattle(capture.battleId) ?: return PvpTimedSubmissionStatus.STALE_TURN
        if (capture.timedOut) return PvpTimedSubmissionStatus.TIMED_OUT
        val status = timer.submitTurn(capture.submission)
        if (status == PvpTimedSubmissionStatus.ACCEPTED || status == PvpTimedSubmissionStatus.ALREADY_SUBMITTED) {
            battles[capture.battleId]?.window?.resolvedPlayers?.add(capture.submission.playerId)
        }
        return status
    }

    @Synchronized
    fun reject(capture: PvpTurnCapture) {
        require(capture.coordinatorIdentity === identity) { "PvP turn capture belongs to another coordinator" }
        if (!capture.timedOut) timerForBattle(capture.battleId)?.rejectTurnSubmission(capture.submission)
    }

    @Synchronized
    fun timeouts(): List<PvpTurnTimeout> {
        val timedOut = ArrayList<PvpTurnTimeout>()
        battles.entries.toList().forEach { (battleId, state) ->
            val timer = timerForBattle(battleId)
            if (timer == null) {
                battles.remove(battleId)
                return@forEach
            }
            val window = state.window ?: return@forEach
            timer.turnTimeouts(window.turnId).forEach { playerId ->
                window.pendingTimeouts[playerId] = PvpTurnTimeout(
                    identity,
                    battleId,
                    playerId,
                    window.requestIdentities.getValue(playerId),
                    timer.remainingPersonalTime(playerId) == 0L,
                )
            }
            timedOut += window.pendingTimeouts.values
        }
        return timedOut
    }

    @Synchronized
    fun acknowledgeTimeout(timeout: PvpTurnTimeout): Boolean {
        require(timeout.coordinatorIdentity === identity) { "PvP timeout belongs to another coordinator" }
        return acknowledgeTimeout(timeout.battleId, timeout.playerId, timeout.requestIdentity, timeout)
    }

    @Synchronized
    fun acknowledgeTimeout(capture: PvpTurnCapture): Boolean {
        require(capture.coordinatorIdentity === identity) { "PvP turn capture belongs to another coordinator" }
        if (!capture.timedOut) return false
        return acknowledgeTimeout(
            capture.battleId,
            capture.submission.playerId,
            capture.requestIdentity,
            expectedTimeout = null,
        )
    }

    private fun acknowledgeTimeout(
        battleId: UUID,
        playerId: UUID,
        requestIdentity: Any,
        expectedTimeout: PvpTurnTimeout?,
    ): Boolean {
        val window = battles[battleId]?.window ?: return false
        val pending = window.pendingTimeouts[playerId] ?: return false
        if (pending.requestIdentity !== requestIdentity || expectedTimeout != null && pending !== expectedTimeout) return false
        window.pendingTimeouts.remove(playerId)
        window.resolvedPlayers += playerId
        return true
    }

    @Synchronized
    fun forget(battleId: UUID) {
        battles.remove(battleId)
    }

    @Synchronized
    fun clear() {
        battles.clear()
    }

    private class BattleTurnState(
        var lastTurnId: Long = 0,
        var window: TurnWindow? = null,
    )

    private class TurnWindow(
        val turnId: Long,
        val requestIdentities: MutableMap<UUID, Any>,
        val resolvedPlayers: MutableSet<UUID> = LinkedHashSet(),
        val pendingTimeouts: MutableMap<UUID, PvpTurnTimeout> = LinkedHashMap(),
    ) {
        fun isResolved(): Boolean = resolvedPlayers.containsAll(requestIdentities.keys)
    }
}
