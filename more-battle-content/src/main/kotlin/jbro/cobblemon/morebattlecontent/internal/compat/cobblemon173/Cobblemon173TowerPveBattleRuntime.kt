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
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat as BrainBattleFormat
import jbro.cobblemon.morebattlecontent.internal.battle.attachReplayableCompletionHandler
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleOutcome
import jbro.cobblemon.morebattlecontent.internal.tower.TowerPreparedPveBattle
import jbro.cobblemon.morebattlecontent.internal.tower.TowerPveBattleRuntime
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjectionNetworking
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramNetworking
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.MinecraftServer
import java.util.UUID

internal data class Cobblemon173ManagedAiBattle(
    val playerId: UUID,
    val playerTeam: List<BattlePokemon>,
    val opponentTeam: List<BattlePokemon>,
    val trainerDisplayNameKey: String,
    val trainerPersonaId: String,
    val trainerAiSkill: Int,
    val trainerProfile: BattleTrainerProfile,
    val learningScopeId: UUID,
    val opponentTeamPreview: BattleOpponentTeamPreviewView,
    val mechanic: MajorBattleMechanic,
    val format: BrainBattleFormat,
    val brainSelectionContext: BattleBrainSelectionContext,
    val contentId: String,
    val diagnosticsLabel: String,
    val unboundedBrainDecision: Boolean = false,
)

internal class Cobblemon173TowerPveBattleRuntime(
    private val playerResolver: (UUID) -> ServerPlayer?,
    private val sessionCompletion: (MinecraftServer, UUID, UUID, TowerBattleOutcome) -> Unit,
    private val sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    private val brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) : TowerPveBattleRuntime<BattlePokemon, BattlePokemon> {
    override fun start(
        prepared: TowerPreparedPveBattle<BattlePokemon, BattlePokemon>,
    ): TowerBattleLaunchResult = startManaged(
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
    )

    internal fun startManaged(prepared: Cobblemon173ManagedAiBattle): TowerBattleLaunchResult {
        val player = playerResolver(prepared.playerId) ?: run {
            MoreBattleContent.LOGGER.error(
                "{} start failed: player {} is not tracked as online",
                prepared.diagnosticsLabel,
                prepared.playerId,
            )
            return TowerBattleLaunchResult.Unavailable
        }
        BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)?.let { existing ->
            MoreBattleContent.LOGGER.error(
                "{} start failed: player {} is already registered in battle {}",
                prepared.diagnosticsLabel,
                player.uuid,
                existing.battleId,
            )
            return TowerBattleLaunchResult.Unavailable
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
            trainerName = prepared.trainerDisplayNameKey,
            actorId = trainerActorId,
            pokemonList = prepared.opponentTeam,
            battleFormat = prepared.format,
            opponentActorId = playerActor.uuid,
            initialOpponentPokemonCount = prepared.playerTeam.size,
            baselineAi = Cobblemon173BaselineAiFactory.create(prepared.trainerAiSkill),
            trainerProfile = prepared.trainerProfile,
            learningScopeId = prepared.learningScopeId,
            trainerPersonaId = prepared.trainerPersonaId,
            primaryBrain = primaryBrain,
            localBrain = localBrain,
            opponentTeamPreview = Cobblemon173PublicTeamPreviewKnowledge.enrich(
                prepared.opponentTeamPreview,
            ),
            unboundedDecisionTime = prepared.unboundedBrainDecision,
            mechanicPolicy = {
                requireNotNull(
                    Cobblemon173BattleRuleHooks.mechanicPolicy(
                        trainerActor.battle.battleId,
                        trainerActorId,
                    ),
                ) { "${prepared.diagnosticsLabel} mechanic rules were not attached before the trainer requested a choice" }
            },
        )
        val canDynamax = prepared.mechanic == jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic.DYNAMAX
        playerActor.canDynamax = canDynamax
        trainerActor.canDynamax = canDynamax
        val actorIds = setOf(playerActor.uuid, trainerActor.uuid)
        val ownerRegistration = try {
            Cobblemon173ManagedTrainerPokemonOwners.register(trainerEntity, prepared.opponentTeam)
        } catch (exception: IllegalArgumentException) {
            MoreBattleContent.LOGGER.error(
                "{} opponent ownership registration failed for {}",
                prepared.diagnosticsLabel,
                player.uuid,
                exception,
            )
            return TowerBattleLaunchResult.Unavailable
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
            MoreBattleContent.LOGGER.error("{} rule registration failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return TowerBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("{} rule registration failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return TowerBattleLaunchResult.Unavailable
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
            MoreBattleContent.LOGGER.error("{} battle creation failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return TowerBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("{} battle creation failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return TowerBattleLaunchResult.Unavailable
        }

        if (result !is SuccessfulBattleStart) {
            runManagedCleanupActions(
                { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                ownerRegistration::close,
            )
            MoreBattleContent.LOGGER.error(
                "{} start was refused by Cobblemon for player {}: {}",
                prepared.diagnosticsLabel,
                player.uuid,
                result,
            )
            return TowerBattleLaunchResult.Unavailable
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
            MoreBattleContent.LOGGER.error("{} battle result failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return TowerBattleLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("{} battle result failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return TowerBattleLaunchResult.Unavailable
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
                            "{} owner release failed for player {} and battle {}",
                            prepared.diagnosticsLabel,
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
                    "{} rules did not attach before battle {} started; ending the unprotected battle",
                    prepared.diagnosticsLabel,
                    battle.battleId,
                )
                Cobblemon173ManagedBattleTermination.end(battle.battleId)
                TowerBattleLaunchResult.Unavailable
            } else {
                Cobblemon173InitialTurnDiagnostics.watch(prepared.diagnosticsLabel, battle)
                ShadowTrainerProjectionNetworking.show(player, battle.battleId, trainerActor.initialPos)
                BattleArenaHologramNetworking.showBetween(player, battle.battleId, player.position(), trainerActor.initialPos)

                attachReplayableCompletionHandler(
                    completion = battle,
                    register = { handler -> battle.onEndHandlers += handler },
                    isComplete = { battle.ended },
                ) { ended ->
                    val outcome = when (playerActor) {
                        in ended.winners -> TowerBattleOutcome.WIN
                        in ended.losers -> TowerBattleOutcome.LOSS
                        else -> null
                    }
                    runManagedCleanupActionsSafely(
                        reportFailure = { failure ->
                            MoreBattleContent.LOGGER.error(
                                "{} end cleanup failed for player {} and battle {}",
                                prepared.diagnosticsLabel,
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
                                        TowerBattleOutcome.WIN -> BattleBrainCloseOutcome.DEFEAT
                                        TowerBattleOutcome.LOSS -> BattleBrainCloseOutcome.VICTORY
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
                TowerBattleLaunchResult.Started(battle.battleId)
            }
        }
    }

    private fun BrainBattleFormat.toCobblemonFormat(): CobblemonBattleFormat = when (this) {
        BrainBattleFormat.SINGLE -> CobblemonBattleFormat.Companion.GEN_9_SINGLES.copy(adjustLevel = 0)
        BrainBattleFormat.DOUBLE -> CobblemonBattleFormat.Companion.GEN_9_DOUBLES.copy(adjustLevel = 0)
    }

    private fun TowerBattleFormat.toBrainFormat(): BrainBattleFormat = when (this) {
        TowerBattleFormat.SINGLE -> BrainBattleFormat.SINGLE
        TowerBattleFormat.DOUBLE -> BrainBattleFormat.DOUBLE
    }

    private fun BrainBattleFormat.toBrainCapability(): BrainCapability = when (this) {
        BrainBattleFormat.SINGLE -> BrainCapability.SINGLE
        BrainBattleFormat.DOUBLE -> BrainCapability.DOUBLE
    }

}
