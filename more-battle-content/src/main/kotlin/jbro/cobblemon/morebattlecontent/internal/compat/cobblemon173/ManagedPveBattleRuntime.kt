package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchResult
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Addon adapter to the lifecycle-owned engine also used by Tower and Better AI test battles. */
internal class ManagedPveBattleRuntime(
    playerResolver: (UUID) -> ServerPlayer?,
    sessionCompletion: (MinecraftServer, UUID, UUID, PveOutcome) -> Unit,
    sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) {
    private val runtime = Cobblemon173TowerPveBattleRuntime(playerResolver,
        { server, player, battle, outcome -> sessionCompletion(server, player, battle, PveOutcome.valueOf(outcome.name)) },
        sessionCancellation, brainRegistry)

    fun startManaged(prepared: ManagedPvePrepared): PveLaunchResult {
        val result = runtime.startManaged(Cobblemon173ManagedAiBattle(
            playerId = prepared.playerId,
            playerTeam = prepared.playerTeam,
            opponentTeam = prepared.opponentTeam,
            trainerDisplayNameKey = prepared.trainerNameKey,
            trainerPersonaId = prepared.trainerId,
            trainerAiSkill = prepared.trainerProfile.skillLevel,
            trainerProfile = prepared.trainerProfile,
            learningScopeId = prepared.learningScopeId,
            opponentTeamPreview = prepared.preview,
            mechanic = prepared.mechanic,
            format = BattleFormat.valueOf(prepared.format.name),
            brainSelectionContext = prepared.brainSelectionContext,
            contentId = prepared.contentId,
            diagnosticsLabel = "Managed PvE",
            appearance = prepared.appearance,
        ))
        return when (result) {
            is TowerBattleLaunchResult.Started -> PveLaunchResult.Started(result.battleId)
            TowerBattleLaunchResult.Unavailable -> PveLaunchResult.Unavailable
        }
    }
}
