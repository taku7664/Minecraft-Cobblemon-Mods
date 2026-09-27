package jbro.cobblemon.mcc.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Addon adapter over the shared managed AI battle engine used by every PvE content. */
internal class ManagedPveBattleRuntime(
    playerResolver: (UUID) -> ServerPlayer?,
    private val sessionCompletion: (MinecraftServer, UUID, UUID, PveOutcome) -> Unit,
    private val sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) {
    private val engine = Cobblemon173ManagedAiBattleEngine(playerResolver, brainRegistry)

    fun startManaged(prepared: ManagedPvePrepared): PveLaunchResult = engine.start(
        Cobblemon173ManagedAiBattle(
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
        ),
    ) { end ->
        val outcome = end.outcome
        if (outcome == null) {
            sessionCancellation(end.server, end.playerId, end.battleId)
        } else {
            sessionCompletion(end.server, end.playerId, end.battleId, outcome)
        }
    }
}
