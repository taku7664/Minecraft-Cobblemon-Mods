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
import jbro.cobblemon.morebattlecontent.internal.factory.assembleFactoryObservationsOrEmpty
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

        try {
            protectManagedBattleStartup(
                releasePendingRegistration = ownerRegistration::close,
                terminateBattle = {},
            ) {
                Cobblemon173BattleRuleHooks.beginRegistration(ManagedBattleContentIds.BATTLE_FACTORY, null, actorIds)
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Battle Factory rule registration failed for {}", player.uuid, failure)
            return FactoryBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Battle Factory rule registration failed for {}", player.uuid, failure)
            return FactoryBattleLaunchResult.Unavailable
        }
        val result = try {
            protectManagedBattleStartup(
                releasePendingRegistration = {
                    runManagedCleanupActions(
                        { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                        ownerRegistration::close,
                    )
                },
                terminateBattle = { Cobblemon173ManagedBattleTermination.endParticipatingPlayer(player.uuid) },
            ) {
                BattleRegistry.startBattle(
                    prepared.request.playerTeam.format.toCobblemonFormat(),
                    BattleSide(playerActor),
                    BattleSide(trainerActor),
                    true,
                )
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Battle Factory battle creation failed for {}", player.uuid, failure)
            return FactoryBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Battle Factory battle creation failed for {}", player.uuid, failure)
            return FactoryBattleLaunchResult.Unavailable
        }
        if (result !is SuccessfulBattleStart) {
            runManagedCleanupActions(
                { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                ownerRegistration::close,
            )
            MoreBattleContent.LOGGER.error(
                "Battle Factory start was refused by Cobblemon for player {}: {}",
                player.uuid,
                result,
            )
            return FactoryBattleLaunchResult.Unavailable
        }
        val battle = try {
            protectManagedBattleStartup(
                releasePendingRegistration = {
                    runManagedCleanupActions(
                        { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                        ownerRegistration::close,
                    )
                },
                terminateBattle = { Cobblemon173ManagedBattleTermination.endParticipatingPlayer(player.uuid) },
            ) {
                result.battle
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Battle Factory battle result failed for {}", player.uuid, failure)
            return FactoryBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Battle Factory battle result failed for {}", player.uuid, failure)
            return FactoryBattleLaunchResult.Unavailable
        }
        return protectManagedBattleStartup(
            releasePendingRegistration = {
                runManagedCleanupActions(
                    { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                    { Cobblemon173BattleRuleHooks.unregister(battle.battleId) },
                    ownerRegistration::close,
                )
            },
            terminateBattle = { Cobblemon173ManagedBattleTermination.end(battle.battleId) },
        ) {
            battle.onEndHandlers += { ended ->
                runManagedCleanupActionsSafely(
                    reportFailure = { failure ->
                        MoreBattleContent.LOGGER.error(
                            "Battle Factory owner release failed for player {} and battle {}",
                            player.uuid,
                            ended.battleId,
                            failure,
                        )
                    },
                    ownerRegistration::close,
                )
            }
            Cobblemon173ManagedPlayerBattleParticipants.attachBattleStores(battle, listOf(playerParticipant))
            if (!Cobblemon173BattleRuleHooks.finishRegistration(battle.battleId)) {
                MoreBattleContent.LOGGER.error(
                    "Battle Factory rules did not attach before battle {} started; ending the unprotected battle",
                    battle.battleId,
                )
                Cobblemon173ManagedBattleTermination.end(battle.battleId)
                FactoryBattleLaunchResult.Unavailable
            } else {
                observationAdapter.attach(battle)
                Cobblemon173InitialTurnDiagnostics.watch("Battle Factory", battle)

                battle.onEndHandlers += { ended ->
                    val playerWon = playerActor in ended.winners
                    val playerLost = playerActor in ended.losers
                    runManagedCleanupActionsSafely(
                        reportFailure = { failure ->
                            MoreBattleContent.LOGGER.error(
                                "Battle Factory end cleanup failed for player {} and battle {}",
                                player.uuid,
                                ended.battleId,
                                failure,
                            )
                        },
                        { Cobblemon173BattleRuleHooks.unregister(ended.battleId) },
                        { ShadowTrainerProjectionNetworking.hide(player, ended.battleId) },
                        { BattleArenaHologramNetworking.hide(player, ended.battleId) },
                        {
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
                        },
                        {
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
                        },
                    )
                }
                ShadowTrainerProjectionNetworking.show(player, battle.battleId, trainerActor.initialPos)
                BattleArenaHologramNetworking.showBetween(player, battle.battleId, player.position(), trainerActor.initialPos)
                FactoryBattleLaunchResult.Started(battle.battleId)
            }
        }
    }

    private fun observations(
        prepared: FactoryPreparedPveBattle<BattlePokemon>,
        playerActor: PlayerBattleActor,
        adapter: Cobblemon173ShowdownObservationAdapter,
    ): Map<String, FactoryOpponentObservation> = assembleFactoryObservationsOrEmpty(
        assemble = {
            Cobblemon173FactoryObservationMapper.map(
                rentalsByToken = prepared.request.opponentTeam,
                battlePokemonIdsByToken = prepared.opponentTeam.mapValues { (_, pokemon) -> pokemon.uuid },
                publicPokemon = adapter.snapshot(playerActor).pokemon,
            )
        },
        reportFailure = { failure ->
            MoreBattleContent.LOGGER.error(
                "Battle Factory public swap observations could not be assembled for run {}",
                prepared.request.runId,
                failure,
            )
        },
    )

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
