package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI
import com.cobblemon.mod.common.battles.ShowdownActionResponse

/** Delegates one complete Cobblemon request to its own baseline AI and validates every returned response. */
internal object Cobblemon173BaselineTurnAdapter {
    fun choose(actor: BattleActor, baseline: BattleAI): Cobblemon173BaselineTurnResult {
        val request = actor.request
            ?: return Cobblemon173BaselineTurnResult.failed(Cobblemon173BaselineTurnStatus.NO_REQUEST)
        return try {
            val responses = if (request.wait) {
                Cobblemon173ActionCandidateAdapter.passResponses(request, actor.activePokemon)
            } else {
                request.iterate(actor.activePokemon) { active, moveset, forceSwitch ->
                    baseline.choose(active, actor.battle, actor.getSide(), moveset, forceSwitch).also { response ->
                        check(response.isValid(active, moveset, forceSwitch)) {
                            "Cobblemon baseline AI returned an invalid response"
                        }
                    }
                }
            }
            if (responses.isEmpty()) {
                if (request.wait) {
                    Cobblemon173BaselineTurnResult.noActionRequired()
                } else {
                    Cobblemon173BaselineTurnResult.failed(Cobblemon173BaselineTurnStatus.NO_LEGAL_ACTIONS)
                }
            } else {
                Cobblemon173BaselineTurnResult.ready(responses)
            }
        } catch (_: Exception) {
            Cobblemon173BaselineTurnResult.failed(Cobblemon173BaselineTurnStatus.FAILED)
        } finally {
            actor.pokemonList.forEach { it.willBeSwitchedIn = false }
        }
    }
}

internal enum class Cobblemon173BaselineTurnStatus {
    READY,
    NO_ACTION_REQUIRED,
    NO_REQUEST,
    NO_LEGAL_ACTIONS,
    FAILED,
}

internal class Cobblemon173BaselineTurnResult private constructor(
    val status: Cobblemon173BaselineTurnStatus,
    responses: List<ShowdownActionResponse>,
) {
    val responses = responses.toList()

    init {
        require(
            (status == Cobblemon173BaselineTurnStatus.READY && responses.isNotEmpty()) ||
                (status != Cobblemon173BaselineTurnStatus.READY && responses.isEmpty()),
        )
    }

    companion object {
        fun ready(responses: List<ShowdownActionResponse>): Cobblemon173BaselineTurnResult {
            require(responses.isNotEmpty())
            return Cobblemon173BaselineTurnResult(Cobblemon173BaselineTurnStatus.READY, responses)
        }

        fun noActionRequired(): Cobblemon173BaselineTurnResult =
            Cobblemon173BaselineTurnResult(Cobblemon173BaselineTurnStatus.NO_ACTION_REQUIRED, emptyList())

        fun failed(status: Cobblemon173BaselineTurnStatus): Cobblemon173BaselineTurnResult {
            require(
                status != Cobblemon173BaselineTurnStatus.READY &&
                    status != Cobblemon173BaselineTurnStatus.NO_ACTION_REQUIRED,
            )
            return Cobblemon173BaselineTurnResult(status, emptyList())
        }
    }
}
