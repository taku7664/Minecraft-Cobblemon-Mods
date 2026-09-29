package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleFormat as BrainBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.mcc.internal.tower.TowerBattleOutcome
import jbro.cobblemon.mcc.internal.tower.TowerPreparedPveBattle
import jbro.cobblemon.mcc.internal.tower.TowerPveBattleRuntime
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Battle Tower adapter over the shared managed AI battle engine. */
internal class Cobblemon173TowerPveBattleRuntime(
    playerResolver: (UUID) -> ServerPlayer?,
    private val sessionCompletion: (MinecraftServer, UUID, UUID, TowerBattleOutcome) -> Unit,
    private val sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) : TowerPveBattleRuntime<BattlePokemon, BattlePokemon> {
    private val engine = Cobblemon173ManagedAiBattleEngine(playerResolver, brainRegistry)

    override fun start(
        prepared: TowerPreparedPveBattle<BattlePokemon, BattlePokemon>,
    ): TowerBattleLaunchResult = when (
        val result = engine.start(
            Cobblemon173ManagedAiBattle(
                playerId = prepared.request.playerId,
                playerTeam = prepared.playerTeam,
                opponentTeam = prepared.opponentTeam,
                trainerDisplayNameKey = prepared.profile.displayNameKey,
                trainerPersonaId = prepared.profile.profileId,
                trainerAiSkill = prepared.profile.aiSkill,
                trainerProfile = prepared.trainerProfile,
                learningScopeId = prepared.request.learningScopeId,
                opponentTeamPreview = prepared.request.playerTeamPreview,
                mechanic = prepared.mechanic,
                format = prepared.request.progress.format.toBrainFormat(),
                brainSelectionContext = prepared.brainSelectionContext,
                contentId = ManagedBattleContentIds.BATTLE_TOWER,
                diagnosticsLabel = "Battle Tower",
            ),
            onEnded = ::finish,
        )
    ) {
        is PveLaunchResult.Started -> TowerBattleLaunchResult.Started(result.battleId)
        PveLaunchResult.Unavailable -> TowerBattleLaunchResult.Unavailable
    }

    private fun finish(end: Cobblemon173ManagedAiBattleEnd) {
        when (end.outcome) {
            PveOutcome.WIN -> sessionCompletion(end.server, end.playerId, end.battleId, TowerBattleOutcome.WIN)
            PveOutcome.LOSS -> sessionCompletion(end.server, end.playerId, end.battleId, TowerBattleOutcome.LOSS)
            null -> sessionCancellation(end.server, end.playerId, end.battleId)
        }
    }

    private fun TowerBattleFormat.toBrainFormat(): BrainBattleFormat = when (this) {
        TowerBattleFormat.SINGLE -> BrainBattleFormat.SINGLE
        TowerBattleFormat.DOUBLE -> BrainBattleFormat.DOUBLE
    }
}
