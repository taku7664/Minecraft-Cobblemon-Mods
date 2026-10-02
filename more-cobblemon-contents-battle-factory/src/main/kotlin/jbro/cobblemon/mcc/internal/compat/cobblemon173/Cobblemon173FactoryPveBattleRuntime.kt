package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.battle.MccBattleTag
import jbro.cobblemon.mcc.api.battle.MccBattleTags
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleEncounterRole
import jbro.cobblemon.mcc.internal.ai.BattleFormat as BrainBattleFormat
import jbro.cobblemon.mcc.internal.factory.FactoryBattleLaunchResult
import jbro.cobblemon.mcc.internal.factory.FactoryBattleFormat
import jbro.cobblemon.mcc.internal.factory.FactoryOpponentObservation
import jbro.cobblemon.mcc.internal.factory.FactoryPreparedPveBattle
import jbro.cobblemon.mcc.internal.factory.FactoryPveBattleRuntime
import jbro.cobblemon.mcc.internal.factory.FactoryRentalSet
import jbro.cobblemon.mcc.internal.factory.assembleFactoryObservationsOrEmpty
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Battle Factory adapter over the shared managed AI battle engine, adding public swap observations. */
internal class Cobblemon173FactoryPveBattleRuntime(
    playerResolver: (UUID) -> ServerPlayer?,
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
    brainRegistry: BattleBrainRegistry = BattleBrainRegistry.global(),
) : FactoryPveBattleRuntime<BattlePokemon> {
    private val engine = Cobblemon173ManagedAiBattleEngine(playerResolver, brainRegistry)

    override fun start(prepared: FactoryPreparedPveBattle<BattlePokemon>): FactoryBattleLaunchResult {
        var observationAdapter: Cobblemon173ShowdownObservationAdapter? = null
        val result = MccBattleTags.during(setOf(prepared.request.playerId), tag(prepared)) { engine.start(
            Cobblemon173ManagedAiBattle(
                playerId = prepared.request.playerId,
                playerTeam = prepared.playerTeam,
                opponentTeam = prepared.opponentTeam.values.toList(),
                trainerDisplayNameKey = prepared.request.trainerNameKey,
                trainerPersonaId = prepared.request.trainerNameKey,
                trainerAiSkill = prepared.request.aiSkill,
                trainerProfile = prepared.trainerProfile,
                learningScopeId = null,
                opponentTeamPreview = null,
                mechanic = null,
                format = prepared.request.playerTeam.format.toBrainFormat(),
                brainSelectionContext = prepared.brainSelectionContext,
                contentId = ManagedBattleContentIds.BATTLE_FACTORY,
                diagnosticsLabel = "Battle Factory",
                strategyBrief = prepared.request.strategyBrief,
                appearance = prepared.request.appearance,
            ),
            onBattleStarted = { battle, trainerActorId ->
                observationAdapter = Cobblemon173ShowdownObservationAdapter(
                    opponentActorId = trainerActorId,
                    initialOpponentPokemonCount = prepared.opponentTeam.size,
                ).also { it.attach(battle) }
            },
        ) { end ->
            when (end.outcome) {
                PveOutcome.WIN -> victory(
                    end.server,
                    end.playerId,
                    prepared.request.runId,
                    end.battleId,
                    prepared.request.opponentTeam,
                    observations(prepared, end.playerActor, observationAdapter),
                )

                PveOutcome.LOSS -> loss(end.server, end.playerId, prepared.request.runId, end.battleId)
                null -> cancellation(end.server, end.playerId, prepared.request.runId, end.battleId)
            }
        } }
        return when (result) {
            is PveLaunchResult.Started -> FactoryBattleLaunchResult.Started(result.battleId)
            PveLaunchResult.Unavailable -> FactoryBattleLaunchResult.Unavailable
        }
    }

    /** `regular`, or `factory_head` for the Factory Head's battles. */
    private fun tag(prepared: FactoryPreparedPveBattle<BattlePokemon>) = MccBattleTag(
        ManagedBattleContentIds.BATTLE_FACTORY,
        if (prepared.brainSelectionContext.encounterRole == BattleEncounterRole.BOSS) "factory_head" else "regular",
    )

    private fun observations(
        prepared: FactoryPreparedPveBattle<BattlePokemon>,
        playerActor: PlayerBattleActor,
        adapter: Cobblemon173ShowdownObservationAdapter?,
    ): Map<String, FactoryOpponentObservation> = assembleFactoryObservationsOrEmpty(
        assemble = {
            Cobblemon173FactoryObservationMapper.map(
                rentalsByToken = prepared.request.opponentTeam,
                battlePokemonIdsByToken = prepared.opponentTeam.mapValues { (_, pokemon) -> pokemon.uuid },
                publicPokemon = requireNotNull(adapter) { "observation adapter was never attached" }
                    .snapshot(playerActor).pokemon,
            )
        },
        reportFailure = { failure ->
            MoreCobblemonContents.LOGGER.error(
                "Battle Factory public swap observations could not be assembled for run {}",
                prepared.request.runId,
                failure,
            )
        },
    )

    private fun FactoryBattleFormat.toBrainFormat(): BrainBattleFormat = when (this) {
        FactoryBattleFormat.SINGLE -> BrainBattleFormat.SINGLE
        FactoryBattleFormat.DOUBLE -> BrainBattleFormat.DOUBLE
    }
}
