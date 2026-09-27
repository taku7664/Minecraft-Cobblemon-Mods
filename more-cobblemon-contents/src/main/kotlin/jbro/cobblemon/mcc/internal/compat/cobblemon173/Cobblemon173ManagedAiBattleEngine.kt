package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.BattleFormat as CobblemonBattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.access.BattleContentAccess
import jbro.cobblemon.mcc.api.access.ContentAccessAction
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseOutcome
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseResult
import jbro.cobblemon.mcc.internal.ai.BattleBrainProviderRole
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleStrategyBrief
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BrainCapability
import jbro.cobblemon.mcc.internal.ai.BattleFormat as BrainBattleFormat
import jbro.cobblemon.mcc.internal.battle.attachReplayableCompletionHandler
import jbro.cobblemon.mcc.internal.presentation.BattleArenaHologramNetworking
import jbro.cobblemon.mcc.internal.shadow.ShadowTrainerProjectionNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** One player against a Brain-driven virtual trainer; every PvE content starts its battles through this. */
data class Cobblemon173ManagedAiBattle(
    val playerId: UUID,
    val playerTeam: List<BattlePokemon>,
    val opponentTeam: List<BattlePokemon>,
    val trainerDisplayNameKey: String,
    val trainerPersonaId: String,
    val trainerAiSkill: Int,
    val trainerProfile: BattleTrainerProfile,
    val learningScopeId: UUID?,
    val opponentTeamPreview: BattleOpponentTeamPreviewView?,
    val mechanic: MajorBattleMechanic?,
    val format: BrainBattleFormat,
    val brainSelectionContext: BattleBrainSelectionContext,
    val contentId: String,
    val diagnosticsLabel: String,
    val unboundedBrainDecision: Boolean = false,
    val appearance: TrainerResourceSkin? = null,
    val strategyBrief: BattleStrategyBrief? = null,
)

/** How a started battle ended for the player; a null outcome means it ended without a winner. */
data class Cobblemon173ManagedAiBattleEnd(
    val server: MinecraftServer,
    val playerId: UUID,
    val battleId: UUID,
    val outcome: PveOutcome?,
    val playerActor: PlayerBattleActor,
)

class Cobblemon173ManagedAiBattleEngine(
    private val playerResolver: (UUID) -> ServerPlayer?,
    private val brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) {
    /**
     * [onBattleStarted] runs once the battle's rules are attached, before the first turn; it receives the
     * virtual trainer's actor id. [onEnded] runs once after the engine's own cleanup.
     */
    fun start(
        prepared: Cobblemon173ManagedAiBattle,
        onBattleStarted: (PokemonBattle, UUID) -> Unit = { _, _ -> },
        onEnded: (Cobblemon173ManagedAiBattleEnd) -> Unit,
    ): PveLaunchResult {
        val player = playerResolver(prepared.playerId) ?: run {
            MoreCobblemonContents.LOGGER.error(
                "{} start failed: player {} is not tracked as online",
                prepared.diagnosticsLabel,
                prepared.playerId,
            )
            return PveLaunchResult.Unavailable
        }
        if (!BattleContentAccess.allow(player, prepared.contentId, ContentAccessAction.START)) return PveLaunchResult.Unavailable
        BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)?.let { existing ->
            MoreCobblemonContents.LOGGER.error(
                "{} start failed: player {} is already registered in battle {}",
                prepared.diagnosticsLabel,
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
        val opponentTeam = Cobblemon173LeadChoice.order(
            team = prepared.opponentTeam,
            preview = prepared.opponentTeamPreview,
            format = prepared.format,
            trainerProfile = prepared.trainerProfile,
            trainerPersonaId = prepared.trainerPersonaId,
            seed = (prepared.learningScopeId?.mostSignificantBits ?: 0L) xor
                prepared.trainerPersonaId.hashCode().toLong() xor trainerActorId.leastSignificantBits,
            brains = listOf(primaryBrain, localBrain),
            diagnosticsLabel = prepared.diagnosticsLabel,
        )
        lateinit var trainerActor: Cobblemon173BrainTrainerBattleActor
        trainerActor = Cobblemon173BrainTrainerBattleActor(
            server = player.server,
            trainerEntity = trainerEntity,
            trainerName = prepared.trainerDisplayNameKey,
            actorId = trainerActorId,
            pokemonList = opponentTeam,
            battleFormat = prepared.format,
            opponentActorId = playerActor.uuid,
            initialOpponentPokemonCount = prepared.playerTeam.size,
            baselineAi = Cobblemon173BaselineAiFactory.create(prepared.trainerAiSkill),
            strategyBrief = prepared.strategyBrief,
            trainerProfile = prepared.trainerProfile,
            learningScopeId = prepared.learningScopeId,
            trainerPersonaId = prepared.trainerPersonaId,
            primaryBrain = primaryBrain,
            localBrain = localBrain,
            opponentTeamPreview = prepared.opponentTeamPreview?.let(Cobblemon173PublicTeamPreviewKnowledge::enrich),
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
        val canDynamax = prepared.mechanic == MajorBattleMechanic.DYNAMAX
        playerActor.canDynamax = canDynamax
        trainerActor.canDynamax = canDynamax
        val actorIds = setOf(playerActor.uuid, trainerActor.uuid)
        val ownerRegistration = try {
            Cobblemon173ManagedTrainerPokemonOwners.register(trainerEntity, prepared.opponentTeam)
        } catch (exception: IllegalArgumentException) {
            MoreCobblemonContents.LOGGER.error(
                "{} opponent ownership registration failed for {}",
                prepared.diagnosticsLabel,
                player.uuid,
                exception,
            )
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
            MoreCobblemonContents.LOGGER.error("{} rule registration failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.error("{} rule registration failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
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
            MoreCobblemonContents.LOGGER.error("{} battle creation failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.error("{} battle creation failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        }

        if (result !is SuccessfulBattleStart) {
            runManagedCleanupActions(
                { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                ownerRegistration::close,
            )
            MoreCobblemonContents.LOGGER.error(
                "{} start was refused by Cobblemon for player {}: {}",
                prepared.diagnosticsLabel,
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
            MoreCobblemonContents.LOGGER.error("{} battle result failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.error("{} battle result failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        }
        try {
            protectManagedBattleStartup(
                releasePendingRegistration = {
                    runManagedCleanupActions(
                        { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                        { Cobblemon173BattleRuleHooks.unregister(battle.battleId) },
                        ownerRegistration::close,
                    )
                },
                terminateBattle = { Cobblemon173ManagedBattleTermination.end(battle.battleId) },
            ) {
                Cobblemon173ManagedBattleLifecycles.register(
                    player.uuid,
                    battle.battleId,
                    prepared.playerTeam,
                    prepared.opponentTeam,
                )
            }
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("{} lifecycle registration failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.error("{} lifecycle registration failed for {}", prepared.diagnosticsLabel, player.uuid, failure)
            return PveLaunchResult.Unavailable
        }
        return protectManagedBattleStartup(
            releasePendingRegistration = {
                runManagedCleanupActions(
                    { Cobblemon173BattleRuleHooks.finishRegistration(null) },
                    { Cobblemon173BattleRuleHooks.unregister(battle.battleId) },
                    { Cobblemon173ManagedBattleLifecycles.abortAndForceRelease(battle.battleId) },
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
                        MoreCobblemonContents.LOGGER.error(
                            "{} owner release failed for player {} and battle {}",
                            prepared.diagnosticsLabel,
                            player.uuid,
                            ended.battleId,
                            failure,
                        )
                    },
                    { Cobblemon173ManagedBattleLifecycles.battleEnded(ended.battleId) },
                    ownerRegistration::close,
                )
            }
            Cobblemon173ManagedPlayerBattleParticipants.attachBattleStores(battle, listOf(playerParticipant))
            if (!Cobblemon173BattleRuleHooks.finishRegistration(battle.battleId)) {
                MoreCobblemonContents.LOGGER.error(
                    "{} rules did not attach before battle {} started; ending the unprotected battle",
                    prepared.diagnosticsLabel,
                    battle.battleId,
                )
                Cobblemon173ManagedBattleTermination.end(battle.battleId)
                PveLaunchResult.Unavailable
            } else {
                onBattleStarted(battle, trainerActorId)
                Cobblemon173InitialTurnDiagnostics.watch(prepared.diagnosticsLabel, battle)
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
                            MoreCobblemonContents.LOGGER.error(
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
                                        PveOutcome.WIN -> BattleBrainCloseOutcome.DEFEAT
                                        PveOutcome.LOSS -> BattleBrainCloseOutcome.VICTORY
                                        null -> BattleBrainCloseOutcome.NO_CONTEST
                                    },
                                    turns = ended.turn,
                                ),
                            )
                        },
                        {
                            onEnded(
                                Cobblemon173ManagedAiBattleEnd(
                                    server = player.server,
                                    playerId = player.uuid,
                                    battleId = ended.battleId,
                                    outcome = outcome,
                                    playerActor = playerActor,
                                ),
                            )
                        },
                    )
                }
                PveLaunchResult.Started(battle.battleId)
            }
        }
    }

    private fun BrainBattleFormat.toCobblemonFormat(): CobblemonBattleFormat = when (this) {
        BrainBattleFormat.SINGLE -> CobblemonBattleFormat.Companion.GEN_9_SINGLES.copy(adjustLevel = 0)
        BrainBattleFormat.DOUBLE -> CobblemonBattleFormat.Companion.GEN_9_DOUBLES.copy(adjustLevel = 0)
    }

    private fun BrainBattleFormat.toBrainCapability(): BrainCapability = when (this) {
        BrainBattleFormat.SINGLE -> BrainCapability.SINGLE
        BrainBattleFormat.DOUBLE -> BrainCapability.DOUBLE
    }
}
