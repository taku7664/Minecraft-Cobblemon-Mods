package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleFormat as CobblemonBattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseOutcome
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseResult
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProviderRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat as BrainBattleFormat
import jbro.cobblemon.morebattlecontent.internal.battle.attachReplayableCompletionHandler
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjectionNetworking
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramNetworking
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.MinecraftServer
import java.util.UUID

internal class ManagedPveBattleRuntime(
    private val playerResolver: (UUID) -> ServerPlayer?,
    private val sessionCompletion: (MinecraftServer, UUID, UUID, PveOutcome) -> Unit,
    private val sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    private val brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) {
    fun startManaged(prepared: ManagedPvePrepared): PveLaunchResult {
        val player = playerResolver(prepared.playerId) ?: run {
            MoreBattleContent.LOGGER.error(
                "Managed PvE start failed: player {} is not tracked as online",
                prepared.playerId,
            )
            return PveLaunchResult.Unavailable
        }
        if (!jbro.cobblemon.morebattlecontent.api.access.BattleContentAccess.allow(
                player, prepared.contentId,
                jbro.cobblemon.morebattlecontent.api.access.ContentAccessAction.START,
            )) return PveLaunchResult.Unavailable
        BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)?.let { existing ->
            MoreBattleContent.LOGGER.error(
                "Managed PvE start failed: player {} is already registered in battle {}",
                player.uuid,
                existing.battleId,
            )
            return PveLaunchResult.Unavailable
        }

        val playerParticipant = Cobblemon173ManagedPlayerBattleParticipants.prepare(player.uuid, prepared.playerTeam)
        Cobblemon173BattlePokemonAppearance.hideHeldItems(prepared.playerTeam, prepared.opponentTeam)
        val playerActor = playerParticipant.actor
        val trainerEntity = Cobblemon173VirtualTrainerAnchor.create(player)
        val trainerActorId = trainerEntity.uuid
        val brainCapability = prepared.format.toBrainCapability()
        val primaryBrain = Cobblemon173BrainProviderResolver.create(
            brainRegistry,
            brainCapability,
            BattleBrainProviderRole.PRIMARY,
            prepared.brainSelectionContext,
        )
        val localBrain = Cobblemon173BrainProviderResolver.create(
            brainRegistry,
            brainCapability,
            BattleBrainProviderRole.LOCAL,
            prepared.brainSelectionContext,
        )
        lateinit var trainerActor: Cobblemon173BrainTrainerBattleActor
        trainerActor = Cobblemon173BrainTrainerBattleActor(
            server = player.server,
            trainerEntity = trainerEntity,
            trainerName = prepared.trainerNameKey,
            actorId = trainerActorId,
            pokemonList = prepared.opponentTeam,
            battleFormat = prepared.format.toBrainFormat(),
            opponentActorId = playerActor.uuid,
            initialOpponentPokemonCount = prepared.playerTeam.size,
            baselineAi = Cobblemon173BaselineAiFactory.create(prepared.trainerProfile.skillLevel),
            trainerProfile = prepared.trainerProfile,
            learningScopeId = prepared.learningScopeId,
            trainerPersonaId = prepared.trainerId,
            primaryBrain = primaryBrain,
            localBrain = localBrain,
            opponentTeamPreview = prepared.preview?.let(Cobblemon173PublicTeamPreviewKnowledge::enrich),
            mechanicPolicy = {
                requireNotNull(
                    Cobblemon173BattleRuleHooks.mechanicPolicy(
                        trainerActor.battle.battleId,
                        trainerActorId,
                    ),
                ) { "Managed PvE mechanic rules were not attached before the trainer requested a choice" }
            },
        )
        val canDynamax = prepared.mechanic == jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic.DYNAMAX
        playerActor.canDynamax = canDynamax
        trainerActor.canDynamax = canDynamax
        val actorIds = setOf(playerActor.uuid, trainerActor.uuid)
        val ownerRegistration = try {
            Cobblemon173ManagedTrainerPokemonOwners.register(trainerEntity, prepared.opponentTeam)
        } catch (exception: IllegalArgumentException) {
            MoreBattleContent.LOGGER.error("Managed PvE opponent ownership registration failed for {}", player.uuid, exception)
            return PveLaunchResult.Unavailable
        }

        try {
            protectManagedBattleStartup(
                releasePendingRegistration = ownerRegistration::close,
                terminateBattle = {},
            ) {
                Cobblemon173BattleRuleHooks.beginRegistration(
                    prepared.contentId,
                    prepared.mechanic,
                    actorIds,
                )
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Managed PvE rule registration failed for {}", player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Managed PvE rule registration failed for {}", player.uuid, failure)
            return PveLaunchResult.Unavailable
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
                    prepared.format.toCobblemonFormat(),
                    BattleSide(playerActor),
                    BattleSide(trainerActor),
                    true,
                )
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Managed PvE battle creation failed for {}", player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Managed PvE battle creation failed for {}", player.uuid, failure)
            return PveLaunchResult.Unavailable
        }

        if (result !is SuccessfulBattleStart) {
            runManagedCleanupActions(
                { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                ownerRegistration::close,
            )
            MoreBattleContent.LOGGER.error(
                "Managed PvE start was refused by Cobblemon for player {}: {}",
                player.uuid,
                result,
            )
            return PveLaunchResult.Unavailable
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
            MoreBattleContent.LOGGER.error("Managed PvE battle result failed for {}", player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Managed PvE battle result failed for {}", player.uuid, failure)
            return PveLaunchResult.Unavailable
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
            attachReplayableCompletionHandler(
                completion = battle,
                register = { handler -> battle.onEndHandlers += handler },
                isComplete = { battle.ended },
            ) { ended ->
                runManagedCleanupActionsSafely(
                    reportFailure = { failure ->
                        MoreBattleContent.LOGGER.error(
                            "Managed PvE owner release failed for player {} and battle {}",
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
                    "Managed PvE rules did not attach before battle {} started; ending the unprotected battle",
                    battle.battleId,
                )
                Cobblemon173ManagedBattleTermination.end(battle.battleId)
                PveLaunchResult.Unavailable
            } else {
                Cobblemon173InitialTurnDiagnostics.watch("Managed PvE", battle)
                ShadowTrainerProjectionNetworking.show(player, battle.battleId, trainerActor.initialPos, prepared.appearance)
                BattleArenaHologramNetworking.showBetween(player, battle.battleId, player.position(), trainerActor.initialPos)

                attachReplayableCompletionHandler(
                    completion = battle,
                    register = { handler -> battle.onEndHandlers += handler },
                    isComplete = { battle.ended },
                ) { ended ->
                    val outcome = when (playerActor) {
                        in ended.winners -> PveOutcome.WIN
                        in ended.losers -> PveOutcome.LOSS
                        else -> null
                    }
                    runManagedCleanupActionsSafely(
                        reportFailure = { failure ->
                            MoreBattleContent.LOGGER.error(
                                "Managed PvE end cleanup failed for player {} and battle {}",
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
                                    outcome = when (outcome) {
                                        PveOutcome.WIN -> BattleBrainCloseOutcome.DEFEAT
                                        PveOutcome.LOSS -> BattleBrainCloseOutcome.VICTORY
                                        null -> BattleBrainCloseOutcome.NO_CONTEST
                                    },
                                    turns = ended.turn,
                                ),
                            )
                        },
                        {
                            if (outcome == null) {
                                sessionCancellation(player.server, player.uuid, ended.battleId)
                            } else {
                                sessionCompletion(player.server, player.uuid, ended.battleId, outcome)
                            }
                        },
                    )
                }
                PveLaunchResult.Started(battle.battleId)
            }
        }
    }

    private fun PveFormat.toCobblemonFormat(): CobblemonBattleFormat = when (this) {
        PveFormat.SINGLE -> CobblemonBattleFormat.Companion.GEN_9_SINGLES.copy(adjustLevel = 0)
        PveFormat.DOUBLE -> CobblemonBattleFormat.Companion.GEN_9_DOUBLES.copy(adjustLevel = 0)
    }

    private fun PveFormat.toBrainFormat(): BrainBattleFormat = when (this) {
        PveFormat.SINGLE -> BrainBattleFormat.SINGLE
        PveFormat.DOUBLE -> BrainBattleFormat.DOUBLE
    }

    private fun PveFormat.toBrainCapability(): BrainCapability = when (this) {
        PveFormat.SINGLE -> BrainCapability.SINGLE
        PveFormat.DOUBLE -> BrainCapability.DOUBLE
    }

}
