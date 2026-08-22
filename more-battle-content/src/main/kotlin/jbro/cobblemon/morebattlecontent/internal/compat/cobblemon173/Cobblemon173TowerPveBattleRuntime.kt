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

internal class Cobblemon173TowerPveBattleRuntime(
    private val playerResolver: (UUID) -> ServerPlayer?,
    private val sessionCompletion: (MinecraftServer, UUID, UUID, TowerBattleOutcome) -> Unit,
    private val sessionCancellation: (MinecraftServer, UUID, UUID) -> Unit,
    private val brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) : TowerPveBattleRuntime<BattlePokemon, BattlePokemon> {
    override fun start(
        prepared: TowerPreparedPveBattle<BattlePokemon, BattlePokemon>,
    ): TowerBattleLaunchResult {
        val player = playerResolver(prepared.request.playerId) ?: return TowerBattleLaunchResult.Unavailable
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) {
            return TowerBattleLaunchResult.Unavailable
        }

        val playerParticipant = Cobblemon173ManagedPlayerBattleParticipants.prepare(player.uuid, prepared.playerTeam)
        Cobblemon173BattlePokemonAppearance.hideHeldItems(prepared.playerTeam, prepared.opponentTeam)
        val playerActor = playerParticipant.actor
        val trainerEntity = Cobblemon173VirtualTrainerAnchor.create(player)
        val trainerActorId = trainerEntity.uuid
        val brainCapability = prepared.request.progress.format.toBrainCapability()
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
            trainerName = prepared.profile.displayNameKey,
            actorId = trainerActorId,
            pokemonList = prepared.opponentTeam,
            battleFormat = prepared.request.progress.format.toBrainFormat(),
            opponentActorId = playerActor.uuid,
            initialOpponentPokemonCount = prepared.playerTeam.size,
            baselineAi = Cobblemon173BaselineAiFactory.create(prepared.profile.aiSkill),
            trainerProfile = prepared.trainerProfile,
            learningScopeId = prepared.request.learningScopeId,
            trainerPersonaId = prepared.profile.profileId,
            primaryBrain = primaryBrain,
            localBrain = localBrain,
            mechanicPolicy = {
                requireNotNull(
                    Cobblemon173BattleRuleHooks.mechanicPolicy(
                        trainerActor.battle.battleId,
                        trainerActorId,
                    ),
                ) { "Battle Tower mechanic rules were not attached before the trainer requested a choice" }
            },
        )
        val canDynamax = prepared.mechanic == jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic.DYNAMAX
        playerActor.canDynamax = canDynamax
        trainerActor.canDynamax = canDynamax
        val actorIds = setOf(playerActor.uuid, trainerActor.uuid)
        val ownerRegistration = try {
            Cobblemon173ManagedTrainerPokemonOwners.register(trainerEntity, prepared.opponentTeam)
        } catch (exception: IllegalArgumentException) {
            MoreBattleContent.LOGGER.error("Battle Tower opponent ownership registration failed for {}", player.uuid, exception)
            return TowerBattleLaunchResult.Unavailable
        }

        Cobblemon173BattleRuleHooks.beginRegistration(ManagedBattleContentIds.BATTLE_TOWER, prepared.mechanic, actorIds)
        val result = try {
            BattleRegistry.startBattle(
                prepared.request.progress.format.toCobblemonFormat(),
                BattleSide(playerActor),
                BattleSide(trainerActor),
                true,
            )
        } catch (exception: RuntimeException) {
            Cobblemon173BattleRuleHooks.finishRegistration(null)
            ownerRegistration.close()
            MoreBattleContent.LOGGER.error("Battle Tower battle creation failed for {}", player.uuid, exception)
            return TowerBattleLaunchResult.Unavailable
        }

        if (result !is SuccessfulBattleStart) {
            Cobblemon173BattleRuleHooks.finishRegistration(null)
            ownerRegistration.close()
            return TowerBattleLaunchResult.Unavailable
        }
        val battle = result.battle
        battle.onEndHandlers += { ownerRegistration.close() }
        Cobblemon173ManagedPlayerBattleParticipants.attachBattleStores(battle, listOf(playerParticipant))
        if (!Cobblemon173BattleRuleHooks.finishRegistration(battle.battleId)) {
            MoreBattleContent.LOGGER.error(
                "Battle Tower rules did not attach before battle {} started; ending the unprotected battle",
                battle.battleId,
            )
            battle.end()
            return TowerBattleLaunchResult.Unavailable
        }
        Cobblemon173InitialTurnDiagnostics.watch("Battle Tower", battle)

        battle.onEndHandlers += { ended ->
            Cobblemon173BattleRuleHooks.unregister(ended.battleId)
            ShadowTrainerProjectionNetworking.hide(player, ended.battleId)
            BattleArenaHologramNetworking.hide(player, ended.battleId)
            val outcome = when (playerActor) {
                in ended.winners -> TowerBattleOutcome.WIN
                in ended.losers -> TowerBattleOutcome.LOSS
                else -> null
            }
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
            try {
                if (outcome == null) {
                    sessionCancellation(player.server, player.uuid, ended.battleId)
                } else {
                    sessionCompletion(player.server, player.uuid, ended.battleId, outcome)
                }
            } catch (exception: RuntimeException) {
                MoreBattleContent.LOGGER.error(
                    "Battle Tower completion failed for player {} and battle {}",
                    player.uuid,
                    ended.battleId,
                    exception,
                )
            }
        }
        ShadowTrainerProjectionNetworking.show(player, battle.battleId, trainerActor.initialPos)
        BattleArenaHologramNetworking.showBetween(player, battle.battleId, player.position(), trainerActor.initialPos)
        return TowerBattleLaunchResult.Started(battle.battleId)
    }

    private fun TowerBattleFormat.toCobblemonFormat(): CobblemonBattleFormat = when (this) {
        TowerBattleFormat.SINGLE -> CobblemonBattleFormat.Companion.GEN_9_SINGLES.copy(adjustLevel = 0)
        TowerBattleFormat.DOUBLE -> CobblemonBattleFormat.Companion.GEN_9_DOUBLES.copy(adjustLevel = 0)
    }

    private fun TowerBattleFormat.toBrainFormat(): BrainBattleFormat = when (this) {
        TowerBattleFormat.SINGLE -> BrainBattleFormat.SINGLE
        TowerBattleFormat.DOUBLE -> BrainBattleFormat.DOUBLE
    }

    private fun TowerBattleFormat.toBrainCapability(): BrainCapability = when (this) {
        TowerBattleFormat.SINGLE -> BrainCapability.SINGLE
        TowerBattleFormat.DOUBLE -> BrainCapability.DOUBLE
    }

}
