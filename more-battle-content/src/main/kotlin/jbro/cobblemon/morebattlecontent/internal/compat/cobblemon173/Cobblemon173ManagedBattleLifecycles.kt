package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleLifecycleRegistry

/** Owns the final entity-release responsibility for every MBC-managed PvE battle. */
internal object Cobblemon173ManagedBattleLifecycles {
    private const val ANIMATED_RECALL_GRACE_TICKS = 40

    private val registry = ManagedBattleLifecycleRegistry<UUID, UUID, Pokemon>()

    fun register(
        playerId: UUID,
        battleId: UUID,
        playerTeam: Iterable<BattlePokemon>,
        opponentTeam: Iterable<BattlePokemon>,
    ) {
        val targets = (playerTeam + opponentTeam)
            .map(BattlePokemon::effectedPokemon)
            .distinctBy(Pokemon::uuid)
        registry.register(playerId, battleId, targets)
    }

    fun battleEnded(battleId: UUID) {
        registry.markEnded(battleId)
    }

    fun abortAndForceRelease(battleId: UUID) {
        registry.takeBattle(battleId)?.let { entry ->
            forceReleaseSafely(entry, "battle setup aborted")
        }
    }

    fun tick() {
        registry.advanceEnded(
            graceTicks = ANIMATED_RECALL_GRACE_TICKS,
            isReleased = { pokemon -> pokemon.entity == null },
        ).forEach { entry ->
            forceReleaseSafely(entry, "recall grace expired")
        }
    }

    fun disconnect(playerId: UUID) {
        registry.takePlayer(playerId).forEach { entry ->
            terminateAndForceReleaseSafely(entry, "player disconnected")
        }
    }

    fun shutdown() {
        registry.takeAll().forEach { entry ->
            terminateAndForceReleaseSafely(entry, "server stopping")
        }
    }

    fun clear() {
        registry.takeAll()
    }

    private fun terminateAndForceReleaseSafely(
        entry: ManagedBattleLifecycleRegistry.Entry<UUID, UUID, Pokemon>,
        reason: String,
    ) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error(
                    "Managed battle {} lifecycle cleanup failed because {}",
                    entry.battleId,
                    reason,
                    failure,
                )
            },
            { Cobblemon173ManagedBattleTermination.end(entry.battleId) },
            { forceRelease(entry.targets) },
        )
    }

    private fun forceReleaseSafely(
        entry: ManagedBattleLifecycleRegistry.Entry<UUID, UUID, Pokemon>,
        reason: String,
    ) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error(
                    "Managed battle {} entity release failed because {}",
                    entry.battleId,
                    reason,
                    failure,
                )
            },
            { forceRelease(entry.targets) },
        )
    }

    private fun forceRelease(targets: Iterable<Pokemon>) {
        runManagedCleanupActions(
            *targets.map { pokemon ->
                {
                    pokemon.entity?.discard()
                    Unit
                }
            }.toTypedArray(),
        )
    }
}
