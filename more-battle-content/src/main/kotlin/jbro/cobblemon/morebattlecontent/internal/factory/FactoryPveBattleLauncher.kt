package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleEncounterRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile

internal class FactoryBattleLaunchRequest(
    val playerId: UUID,
    val runId: UUID,
    val battleNumber: Int,
    val playerTeam: FactoryRentalTeam,
    val levelMode: FactoryLevelMode,
    opponentTeam: Map<UUID, FactoryRentalSet>,
    val trainerNameKey: String,
    val aiSkill: Int,
    val strategyBrief: BattleStrategyBrief,
) {
    val opponentTeam: Map<UUID, FactoryRentalSet> =
        Collections.unmodifiableMap(LinkedHashMap(opponentTeam))

    init {
        require(battleNumber > 0) { "Factory battle number must be positive" }
        require(trainerNameKey.isNotBlank()) { "Factory trainer name key cannot be blank" }
        require(aiSkill in 0..5) { "Factory trainer AI skill must be between 0 and 5" }
        FactoryRentalTeam(playerTeam.format, this.opponentTeam.values.toList())
    }
}

internal sealed interface FactoryBattleLaunchResult {
    data class Started(val battleId: UUID) : FactoryBattleLaunchResult
    data object Unavailable : FactoryBattleLaunchResult
}

internal fun interface FactoryBattleLauncher {
    fun launch(request: FactoryBattleLaunchRequest): FactoryBattleLaunchResult
}

internal class FactoryPreparedPveBattle<M>(
    val request: FactoryBattleLaunchRequest,
    playerTeam: Collection<M>,
    opponentTeam: Map<UUID, M>,
    val trainerProfile: BattleTrainerProfile,
) {
    val playerTeam: List<M> = Collections.unmodifiableList(ArrayList(playerTeam))
    val opponentTeam: Map<UUID, M> = Collections.unmodifiableMap(LinkedHashMap(opponentTeam))
    val battleNumber: Int
        get() = request.battleNumber
    val brainSelectionContext: BattleBrainSelectionContext = BattleBrainSelectionContext(
        contentId = BattleBrainContentIds.BATTLE_FACTORY,
        encounterRole = if (FactoryProgression.isFactoryHeadBattle(request.battleNumber, request.playerTeam.format)) {
            BattleEncounterRole.BOSS
        } else {
            BattleEncounterRole.REGULAR
        },
        difficultyTier = trainerProfile.difficulty.tier,
    )

    init {
        require(this.playerTeam.size == request.playerTeam.format.selectionSize)
        require(this.opponentTeam.keys == request.opponentTeam.keys)
    }
}

internal fun interface FactoryPveBattleRuntime<M> {
    fun start(prepared: FactoryPreparedPveBattle<M>): FactoryBattleLaunchResult
}

internal class FactoryPveBattleLauncher<M>(
    private val playerMemberFactory: (FactoryRentalSet, FactoryLevelMode) -> M,
    private val opponentMemberFactory: (FactoryRentalSet, FactoryLevelMode) -> M,
    private val runtime: FactoryPveBattleRuntime<M>,
) : FactoryBattleLauncher {
    override fun launch(request: FactoryBattleLaunchRequest): FactoryBattleLaunchResult {
        val playerMembers = materialize(request.playerTeam.sets, request.levelMode, playerMemberFactory)
            ?: return FactoryBattleLaunchResult.Unavailable
        val opponentMembers = LinkedHashMap<UUID, M>()
        for ((token, set) in request.opponentTeam) {
            val member = try {
                opponentMemberFactory(set, request.levelMode)
            } catch (_: RuntimeException) {
                return FactoryBattleLaunchResult.Unavailable
            }
            opponentMembers[token] = member
        }
        return runtime.start(
            FactoryPreparedPveBattle(
                request,
                playerMembers,
                opponentMembers,
                FactoryBattleDifficultyPolicy.resolve(
                    request.battleNumber,
                    request.playerTeam.format,
                    request.aiSkill,
                ),
            ),
        )
    }

    private fun materialize(
        sets: List<FactoryRentalSet>,
        levelMode: FactoryLevelMode,
        factory: (FactoryRentalSet, FactoryLevelMode) -> M,
    ): List<M>? {
        val members = ArrayList<M>(sets.size)
        for (set in sets) {
            val member = try {
                factory(set, levelMode)
            } catch (_: RuntimeException) {
                return null
            }
            members += member
        }
        return members
    }
}

internal class FactoryRunBattleService(
    private val launcher: FactoryBattleLauncher,
) {
    fun begin(
        playerId: UUID,
        session: FactoryRunSession,
        opponentTeam: Map<UUID, FactoryRentalSet>,
        trainerNameKey: String,
        aiSkill: Int,
        strategyBrief: BattleStrategyBrief,
    ): FactoryBattleLaunchResult = synchronized(session) {
        check(session.phase == FactoryRunPhase.READY && session.activeBattleId == null) {
            "Factory run is not ready for a battle"
        }
        val result = launcher.launch(
            FactoryBattleLaunchRequest(
                playerId = playerId,
                runId = session.runId,
                battleNumber = Math.addExact(session.wins, 1),
                playerTeam = session.team,
                levelMode = session.levelMode,
                opponentTeam = opponentTeam,
                trainerNameKey = trainerNameKey,
                aiSkill = aiSkill,
                strategyBrief = strategyBrief,
            ),
        )
        if (result is FactoryBattleLaunchResult.Started) session.beginBattle(result.battleId)
        result
    }
}
