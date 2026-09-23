package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.ForfeitActionResponse
import com.cobblemon.mod.common.battles.ai.RandomBattleAI
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleTurnCoordinator
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpMatchTimer
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTurnCapture

/** Adapts Cobblemon request objects to the pure PvP turn-clock coordinator. */
internal class Cobblemon173PvpTurnHooks(
    timerForBattle: (java.util.UUID) -> PvpMatchTimer?,
) {
    private val coordinator = PvpBattleTurnCoordinator(timerForBattle)
    private val timeoutAi = RandomBattleAI()

    fun observe(battle: PokemonBattle) {
        if (battle.ended) {
            coordinator.forget(battle.battleId)
            return
        }
        val requests = battle.actors.mapNotNull { actor ->
            val request = actor.request
            if (actor.mustChoose && request != null) actor.uuid to request else null
        }.toMap(LinkedHashMap())
        coordinator.observe(battle.battleId, requests)
    }

    fun capture(actor: BattleActor): PvpTurnCapture? {
        val request = actor.request ?: return null
        return coordinator.capture(actor.battle.battleId, actor.uuid, request)
    }

    fun accept(capture: PvpTurnCapture) {
        coordinator.accept(capture)
    }

    fun reject(capture: PvpTurnCapture) {
        coordinator.reject(capture)
    }

    fun resolveTimedOut(actor: BattleActor, capture: PvpTurnCapture) {
        if (!capture.timedOut || actor.request !== capture.requestIdentity || !actor.mustChoose) return
        applyTimeout(actor, capture.personalTimeExhausted)
    }

    fun processTimeouts() {
        runManagedCleanupForEachSafely(
            items = coordinator.timeouts(),
            reportFailure = { timeout, failure ->
                reportManagedCleanupFailureSafely(failure) {
                    MoreBattleContent.LOGGER.error(
                        "PvP timeout processing failed for battle {} and player {}",
                        timeout.battleId,
                        timeout.playerId,
                        it,
                    )
                }
            },
        ) { timeout ->
            val battle = BattleRegistry.getBattle(timeout.battleId) ?: return@runManagedCleanupForEachSafely
            val actor = battle.getActor(timeout.playerId) ?: return@runManagedCleanupForEachSafely
            if (actor.mustChoose && actor.request === timeout.requestIdentity) {
                applyTimeout(actor, timeout.personalTimeExhausted)
            }
        }
    }

    fun forget(battleId: java.util.UUID) {
        coordinator.forget(battleId)
    }

    fun clear() {
        coordinator.clear()
    }

    private fun applyTimeout(actor: BattleActor, personalTimeExhausted: Boolean) {
        val expectedRequest = actor.request ?: return abort(actor, "request disappeared before timeout resolution")
        if (!actor.mustChoose) return abort(actor, "choice closed before timeout resolution")
        val responses = if (personalTimeExhausted) {
            listOf(ForfeitActionResponse())
        } else {
            val baseline = Cobblemon173BaselineTurnAdapter.choose(actor, timeoutAi)
            if (baseline.status == Cobblemon173BaselineTurnStatus.READY) {
                baseline.responses
            } else {
                listOf(ForfeitActionResponse())
            }
        }
        try {
            actor.setActionResponses(responses)
        } catch (failure: RuntimeException) {
            reportTimeoutFailureSafely(actor, "action", failure)
            recoverWithForfeit(actor, expectedRequest)
        } catch (failure: LinkageError) {
            reportTimeoutFailureSafely(actor, "action API", failure)
            recoverWithForfeit(actor, expectedRequest)
        }
        if (!actor.battle.ended && actor.mustChoose && actor.request === expectedRequest) {
            recoverWithForfeit(actor, expectedRequest)
        }
    }

    private fun recoverWithForfeit(actor: BattleActor, expectedRequest: Any) {
        if (actor.battle.ended) return
        if (!actor.mustChoose || actor.request !== expectedRequest) {
            abort(actor, "timeout action failed after its request closed")
            return
        }
        try {
            actor.setActionResponses(listOf(ForfeitActionResponse()))
        } catch (failure: RuntimeException) {
            reportTimeoutFailureSafely(actor, "forfeit", failure)
            abort(actor, "timeout forfeit failed")
            return
        } catch (failure: LinkageError) {
            reportTimeoutFailureSafely(actor, "forfeit API", failure)
            abort(actor, "timeout forfeit API failed")
            return
        }
        if (!actor.battle.ended && actor.mustChoose && actor.request === expectedRequest) {
            abort(actor, "timeout forfeit did not resolve the request")
        }
    }

    private fun abort(actor: BattleActor, reason: String) {
        reportTimeoutAbortSafely(actor, reason)
        try {
            Cobblemon173ManagedBattleTermination.end(actor.battle.battleId)
        } catch (failure: RuntimeException) {
            reportTimeoutFailureSafely(actor, "cleanup", failure)
        } catch (failure: LinkageError) {
            reportTimeoutFailureSafely(actor, "cleanup API", failure)
        }
    }

    private fun reportTimeoutFailureSafely(actor: BattleActor, stage: String, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error(
                "PvP timeout {} failed for actor {}",
                stage,
                compatibilityCallOrNull { actor.uuid },
                it,
            )
        }
    }

    private fun reportTimeoutAbortSafely(actor: BattleActor, reason: String) {
        try {
            MoreBattleContent.LOGGER.error(
                "Aborting PvP battle {} because {}",
                compatibilityCallOrNull { actor.battle.battleId },
                reason,
            )
        } catch (_: RuntimeException) {
            // Diagnostics cannot prevent the required battle termination.
        } catch (_: LinkageError) {
            // Logging integrations are optional at this compatibility boundary.
        }
    }
}
