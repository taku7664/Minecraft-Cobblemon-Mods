package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleFormat as CobblemonBattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseOutcome
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseResult
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProviderRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat as BrainBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryOpponentObservation
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPreparedPveBattle
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPveBattleRuntime
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjectionNetworking
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal class Cobblemon173FactoryPveBattleRuntime(
    private val playerResolver: (UUID) -> ServerPlayer?,
    private val victory: (
        MinecraftServer,
        UUID,
        UUID,
        UUID,
        Map<UUID, FactoryRentalSet>,
        Map<String, FactoryOpponentObservation>,
    ) -> Unit,
    private val loss: (MinecraftServer, UUID, UUID, UUID) -> Unit,
    private val cancellation: (MinecraftServer, UUID, UUID, UUID) -> Unit,
    private val brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) : FactoryPveBattleRuntime<BattlePokemon> {
    override fun start(prepared: FactoryPreparedPveBattle<BattlePokemon>): FactoryBattleLaunchResult {
        val player = playerResolver(prepared.request.playerId) ?: run {
            MoreBattleContent.LOGGER.error(
                "Battle Factory start failed: player {} is not tracked as online",
                prepared.request.playerId,
            )
            return FactoryBattleLaunchResult.Unavailable
        }
        BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)?.let { existing ->
            MoreBattleContent.LOGGER.error(
                "Battle Factory start failed: player {} is already registered in battle {}",
                player.uuid,
                existing.battleId,
            )
            return FactoryBattleLaunchResult.Unavailable
        }

        val playerParticipant = Cobblemon173ManagedPlayerBattleParticipants.prepare(player.uuid, prepared.playerTeam)
        Cobblemon173BattlePokemonAppearance.hideHeldItems(prepared.playerTeam, prepared.opponentTeam.values)
        val playerActor = playerParticipant.actor
        val trainerEntity = Cobblemon173VirtualTrainerAnchor.create(player)
        val trainerActorId = trainerEntity.uuid
        val capability = prepared.request.playerTeam.format.toBrainCapability()
        lateinit var trainerActor: Cobblemon173BrainTrainerBattleActor
        trainerActor = Cobblemon173BrainTrainerBattleActor(
            server = player.server,
            trainerEntity = trainerEntity,
            trainerName = prepared.request.trainerNameKey,
            actorId = trainerActorId,
            pokemonList = prepared.opponentTeam.values.toList(),
            battleFormat = prepared.request.playerTeam.format.toBrainFormat(),
            opponentActorId = playerActor.uuid,
            initialOpponentPokemonCount = prepared.playerTeam.size,
            baselineAi = Cobblemon173BaselineAiFactory.create(prepared.request.aiSkill),
            trainerProfile = prepared.trainerProfile,
            trainerPersonaId = prepared.request.trainerNameKey,
            primaryBrain = Cobblemon173BrainProviderResolver.create(
                brainRegistry,
                capability,
                BattleBrainProviderRole.PRIMARY,
                prepared.brainSelectionContext,
            ),
            localBrain = Cobblemon173BrainProviderResolver.create(
                brainRegistry,
                capability,
                BattleBrainProviderRole.LOCAL,
                prepared.brainSelectionContext,
            ),
            mechanicPolicy = {
                requireNotNull(Cobblemon173BattleRuleHooks.mechanicPolicy(trainerActor.battle.battleId, trainerActorId)) {
                    "Battle Factory rules were not attached before the trainer requested a choice"
                }
            },
            strategyBrief = prepared.request.strategyBrief,
        )
        playerActor.canDynamax = false
        trainerActor.canDynamax = false
        val actorIds = setOf(playerActor.uuid, trainerActor.uuid)
        val observationAdapter = Cobblemon173ShowdownObservationAdapter(
            opponentActorId = trainerActorId,
            initialOpponentPokemonCount = prepared.opponentTeam.size,
        )
        val ownerRegistration = try {
            Cobblemon173ManagedTrainerPokemonOwners.register(trainerEntity, prepared.opponentTeam.values)
        } catch (exception: IllegalArgumentException) {
            MoreBattleContent.LOGGER.error("Battle Factory opponent ownership registration failed for {}", player.uuid, exception)
            return FactoryBattleLaunchResult.Unavailable
        }

        Cobblemon173BattleRuleHooks.beginRegistration(ManagedBattleContentIds.BATTLE_FACTORY, null, actorIds)
        val result = try {
            BattleRegistry.startBattle(
                prepared.request.playerTeam.format.toCobblemonFormat(),
                BattleSide(playerActor),
                BattleSide(trainerActor),
                true,
            )
        } catch (exception: RuntimeException) {
            Cobblemon173BattleRuleHooks.finishRegistration(null)
            ownerRegistration.close()
            MoreBattleContent.LOGGER.error("Battle Factory battle creation failed for {}", player.uuid, exception)
            return FactoryBattleLaunchResult.Unavailable
        }
        if (result !is SuccessfulBattleStart) {
            Cobblemon173BattleRuleHooks.finishRegistration(null)
            ownerRegistration.close()
            MoreBattleContent.LOGGER.error(
                "Battle Factory start was refused by Cobblemon for player {}: {}",
                player.uuid,
                result,
            )
            return FactoryBattleLaunchResult.Unavailable
        }
        val battle = result.battle
        battle.onEndHandlers += { ownerRegistration.close() }
        Cobblemon173ManagedPlayerBattleParticipants.attachBattleStores(battle, listOf(playerParticipant))
        if (!Cobblemon173BattleRuleHooks.finishRegistration(battle.battleId)) {
            MoreBattleContent.LOGGER.error(
                "Battle Factory rules did not attach before battle {} started; ending the unprotected battle",
                battle.battleId,
            )
            battle.end()
            return FactoryBattleLaunchResult.Unavailable
        }
        observationAdapter.attach(battle)
        Cobblemon173InitialTurnDiagnostics.watch("Battle Factory", battle)

        battle.onEndHandlers += { ended ->
            Cobblemon173BattleRuleHooks.unregister(ended.battleId)
            ShadowTrainerProjectionNetworking.hide(player, ended.battleId)
            BattleArenaHologramNetworking.hide(player, ended.battleId)
            val playerWon = playerActor in ended.winners
            val playerLost = playerActor in ended.losers
            trainerActor.closeBrains(
                BattleBrainCloseResult(
                    outcome = when {
                        playerWon -> BattleBrainCloseOutcome.DEFEAT
                        playerLost -> BattleBrainCloseOutcome.VICTORY
                        else -> BattleBrainCloseOutcome.NO_CONTEST
                    },
                    turns = ended.turn,
                ),
            )
            try {
                when {
                    playerWon -> victory(
                        player.server,
                        player.uuid,
                        prepared.request.runId,
                        ended.battleId,
                        prepared.request.opponentTeam,
                        observations(prepared, playerActor, observationAdapter),
                    )

                    playerLost -> loss(player.server, player.uuid, prepared.request.runId, ended.battleId)
                    else -> cancellation(player.server, player.uuid, prepared.request.runId, ended.battleId)
                }
            } catch (exception: RuntimeException) {
                MoreBattleContent.LOGGER.error(
                    "Battle Factory completion failed for player {} and battle {}",
                    player.uuid,
                    ended.battleId,
                    exception,
                )
            }
        }
        ShadowTrainerProjectionNetworking.show(player, battle.battleId, trainerActor.initialPos)
        BattleArenaHologramNetworking.showBetween(player, battle.battleId, player.position(), trainerActor.initialPos)
        return FactoryBattleLaunchResult.Started(battle.battleId)
    }

    private fun observations(
        prepared: FactoryPreparedPveBattle<BattlePokemon>,
        playerActor: PlayerBattleActor,
        adapter: Cobblemon173ShowdownObservationAdapter,
    ): Map<String, FactoryOpponentObservation> = try {
        Cobblemon173FactoryObservationMapper.map(
            rentalsByToken = prepared.request.opponentTeam,
            battlePokemonIdsByToken = prepared.opponentTeam.mapValues { (_, pokemon) -> pokemon.uuid },
            publicPokemon = adapter.snapshot(playerActor).pokemon,
        )
    } catch (exception: RuntimeException) {
        MoreBattleContent.LOGGER.error(
            "Battle Factory public swap observations could not be assembled for run {}",
            prepared.request.runId,
            exception,
        )
        emptyMap()
    }

    private fun FactoryBattleFormat.toCobblemonFormat(): CobblemonBattleFormat = when (this) {
        FactoryBattleFormat.SINGLE -> CobblemonBattleFormat.GEN_9_SINGLES.copy(adjustLevel = 0)
        FactoryBattleFormat.DOUBLE -> CobblemonBattleFormat.GEN_9_DOUBLES.copy(adjustLevel = 0)
    }

    private fun FactoryBattleFormat.toBrainFormat(): BrainBattleFormat = when (this) {
        FactoryBattleFormat.SINGLE -> BrainBattleFormat.SINGLE
        FactoryBattleFormat.DOUBLE -> BrainBattleFormat.DOUBLE
    }

    private fun FactoryBattleFormat.toBrainCapability(): BrainCapability = when (this) {
        FactoryBattleFormat.SINGLE -> BrainCapability.SINGLE
        FactoryBattleFormat.DOUBLE -> BrainCapability.DOUBLE
    }
}
