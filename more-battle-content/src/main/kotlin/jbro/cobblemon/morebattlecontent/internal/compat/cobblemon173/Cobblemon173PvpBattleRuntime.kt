package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleFormat as CobblemonBattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.battle.attachReplayableCompletionHandler
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleRuntime
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpPreparedBattle
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerSubmittedMechanic
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal class Cobblemon173PvpBattleRuntime(
    private val playerResolver: (UUID) -> ServerPlayer?,
    private val completion: (MinecraftServer, UUID, UUID, UUID, UUID) -> Unit,
    private val cancellation: (MinecraftServer, UUID, UUID) -> Unit,
) : PvpBattleRuntime<BattlePokemon> {
    override fun start(prepared: PvpPreparedBattle<BattlePokemon>): PvpBattleLaunchResult {
        val request = prepared.request
        val first = playerResolver(request.firstPlayerId) ?: run {
            MoreBattleContent.LOGGER.error(
                "PvP start failed for match {}: player {} is not tracked as online",
                request.matchId,
                request.firstPlayerId,
            )
            return PvpBattleLaunchResult.Unavailable
        }
        val second = playerResolver(request.secondPlayerId) ?: run {
            MoreBattleContent.LOGGER.error(
                "PvP start failed for match {}: player {} is not tracked as online",
                request.matchId,
                request.secondPlayerId,
            )
            return PvpBattleLaunchResult.Unavailable
        }
        if (first.server !== second.server) {
            MoreBattleContent.LOGGER.error(
                "PvP start failed for match {}: players {} and {} are on different servers",
                request.matchId,
                first.uuid,
                second.uuid,
            )
            return PvpBattleLaunchResult.Unavailable
        }
        listOf(first, second).forEach { player ->
            BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)?.let { existing ->
                MoreBattleContent.LOGGER.error(
                    "PvP start failed for match {}: player {} is already registered in battle {}",
                    request.matchId,
                    player.uuid,
                    existing.battleId,
                )
                return PvpBattleLaunchResult.Unavailable
            }
        }

        val firstParticipant = Cobblemon173ManagedPlayerBattleParticipants.prepare(first.uuid, prepared.firstTeam)
        val secondParticipant = Cobblemon173ManagedPlayerBattleParticipants.prepare(second.uuid, prepared.secondTeam)
        Cobblemon173BattlePokemonAppearance.hideHeldItems(prepared.firstTeam, prepared.secondTeam)
        val firstActor = firstParticipant.actor
        val secondActor = secondParticipant.actor
        val mechanics = request.immutableEnabledMechanics
        firstActor.canDynamax = PvpBattleMechanic.DYNAMAX in mechanics
        secondActor.canDynamax = PvpBattleMechanic.DYNAMAX in mechanics
        val actorIds = setOf(firstActor.uuid, secondActor.uuid)
        try {
            protectManagedBattleStartup(
                releasePendingRegistration = { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                terminateBattle = {},
            ) {
                Cobblemon173BattleRuleHooks.beginRegistrationMultiple(
                    ManagedBattleContentIds.PVP,
                    mechanics.mapTo(LinkedHashSet()) { mechanic ->
                        when (mechanic) {
                            PvpBattleMechanic.MEGA -> TowerSubmittedMechanic.MEGA
                            PvpBattleMechanic.DYNAMAX -> TowerSubmittedMechanic.DYNAMAX
                            PvpBattleMechanic.TERA -> TowerSubmittedMechanic.TERA
                            PvpBattleMechanic.Z_MOVE -> TowerSubmittedMechanic.Z_MOVE
                        }
                    },
                    actorIds,
                )
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("PvP rule registration failed for match {}", request.matchId, failure)
            return PvpBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("PvP rule registration failed for match {}", request.matchId, failure)
            return PvpBattleLaunchResult.Unavailable
        }
        val terminatePartialBattle = {
            val partialBattles = listOf(first.uuid, second.uuid)
                .mapNotNull(BattleRegistry::getBattleByParticipatingPlayerId)
                .distinctBy { partial -> partial.battleId }
            partialBattles.forEach { partial -> Cobblemon173ManagedBattleTermination.end(partial.battleId) }
        }
        val result = try {
            protectManagedBattleStartup(
                releasePendingRegistration = { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                terminateBattle = terminatePartialBattle,
            ) {
                BattleRegistry.startBattle(
                    request.format.toCobblemonFormat(),
                    BattleSide(firstActor),
                    BattleSide(secondActor),
                    true,
                )
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("PvP battle creation failed for match {}", request.matchId, failure)
            return PvpBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("PvP battle creation failed for match {}", request.matchId, failure)
            return PvpBattleLaunchResult.Unavailable
        }
        if (result !is SuccessfulBattleStart) {
            Cobblemon173BattleRuleHooks.finishRegistration(null)
            MoreBattleContent.LOGGER.error(
                "PvP start was refused by Cobblemon for match {}: {}",
                request.matchId,
                result,
            )
            return PvpBattleLaunchResult.Unavailable
        }
        val battle = try {
            protectManagedBattleStartup(
                releasePendingRegistration = { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                terminateBattle = terminatePartialBattle,
            ) {
                result.battle
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("PvP battle result failed for match {}", request.matchId, failure)
            return PvpBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("PvP battle result failed for match {}", request.matchId, failure)
            return PvpBattleLaunchResult.Unavailable
        }
        return protectManagedBattleStartup(
            releasePendingRegistration = {
                runManagedCleanupActions(
                    { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                    { Cobblemon173BattleRuleHooks.unregister(battle.battleId) },
                )
            },
            terminateBattle = { Cobblemon173ManagedBattleTermination.end(battle.battleId) },
        ) {
            Cobblemon173ManagedPlayerBattleParticipants.attachBattleStores(
                battle,
                listOf(firstParticipant, secondParticipant),
            )
            if (!Cobblemon173BattleRuleHooks.finishRegistration(battle.battleId)) {
                MoreBattleContent.LOGGER.error(
                    "PvP rules did not attach before battle {} started; ending the unprotected battle",
                    battle.battleId,
                )
                Cobblemon173ManagedBattleTermination.end(battle.battleId)
                PvpBattleLaunchResult.Unavailable
            } else {
                Cobblemon173InitialTurnDiagnostics.watch("PvP", battle)
                attachReplayableCompletionHandler(
                    completion = battle,
                    register = { handler -> battle.onEndHandlers += handler },
                    isComplete = { battle.ended },
                ) { ended ->
                    runManagedCleanupActionsSafely(
                        reportFailure = { failure ->
                            MoreBattleContent.LOGGER.error(
                                "PvP end cleanup failed for match {} and battle {}",
                                request.matchId,
                                ended.battleId,
                                failure,
                            )
                        },
                        { Cobblemon173BattleRuleHooks.unregister(ended.battleId) },
                        {
                            when {
                                firstActor in ended.winners && secondActor in ended.losers ->
                                    completion(first.server, request.matchId, first.uuid, second.uuid, ended.battleId)
                                secondActor in ended.winners && firstActor in ended.losers ->
                                    completion(first.server, request.matchId, second.uuid, first.uuid, ended.battleId)
                                else -> cancellation(first.server, request.matchId, ended.battleId)
                            }
                        },
                    )
                }
                PvpBattleLaunchResult.Started(battle.battleId)
            }
        }
    }

    private fun PvpBattleFormat.toCobblemonFormat(): CobblemonBattleFormat = when (this) {
        PvpBattleFormat.SINGLE -> CobblemonBattleFormat.Companion.GEN_9_SINGLES.copy(adjustLevel = 0)
        PvpBattleFormat.DOUBLE -> CobblemonBattleFormat.Companion.GEN_9_DOUBLES.copy(adjustLevel = 0)
    }
}
