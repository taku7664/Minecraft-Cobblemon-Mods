package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.internal.tower.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Tower adapter only. Shared execution does not know Tower progression or team selection. */
internal class Cobblemon173TowerPveBattleRuntime(
    playerResolver: (UUID) -> ServerPlayer?,
    sessionCompletion: (MinecraftServer, UUID, UUID, TowerBattleOutcome) -> Unit,
    sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) : TowerPveBattleRuntime<BattlePokemon, BattlePokemon> {
    private val runtime = ManagedPveBattleRuntime(playerResolver,
        { server, player, battle, outcome -> sessionCompletion(server, player, battle, TowerBattleOutcome.valueOf(outcome.name)) },
        sessionCancellation, brainRegistry)

    override fun start(prepared: TowerPreparedPveBattle<BattlePokemon, BattlePokemon>): TowerBattleLaunchResult {
        val result = runtime.startManaged(ManagedPvePrepared(
            prepared.request.playerId, ManagedBattleContentIds.BATTLE_TOWER,
            prepared.profile.profileId, prepared.profile.displayNameKey, PveFormat.valueOf(prepared.request.progress.format.name),
            prepared.mechanic, prepared.playerTeam, prepared.opponentTeam, prepared.trainerProfile,
            prepared.brainSelectionContext, prepared.request.learningScopeId, prepared.request.playerTeamPreview,
        ))
        return when (result) {
            is PveLaunchResult.Started -> TowerBattleLaunchResult.Started(result.battleId)
            PveLaunchResult.Unavailable -> TowerBattleLaunchResult.Unavailable
        }
    }
}
