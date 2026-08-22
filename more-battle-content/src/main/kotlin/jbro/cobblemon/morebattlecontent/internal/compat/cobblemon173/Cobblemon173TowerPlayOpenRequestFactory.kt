package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordService
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgressRecordCodec
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.TowerLegendaryClassPolicy
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRecordContract
import jbro.cobblemon.morebattlecontent.internal.tower.TOWER_BATTLE_LEVEL_CAP
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayOpenRequest
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayOpenRequestFactory
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPartySlot
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.MinecraftServer
import java.util.UUID

internal object Cobblemon173TowerPlayOpenRequestFactory {
    fun create(
        player: ServerPlayer,
        initialFormat: TowerBattleFormat = TowerBattleFormat.SINGLE,
    ): TowerPlayOpenRequest {
        val server = player.server
        val playerId = player.uuid
        return TowerPlayOpenRequestFactory(
            partySource = { requestedPlayerId ->
                require(requestedPlayerId == playerId) { "Player changed while reading the Battle Tower party" }
                readParty(player)
            },
            progressSource = { requestedPlayerId, format ->
                require(requestedPlayerId == playerId) { "Player changed while reading Battle Tower progress" }
                readProgress(server, playerId, format)
            },
            bpSource = { requestedPlayerId ->
                require(requestedPlayerId == playerId) { "Player changed while reading BP" }
                BattlePointService.balance(server, playerId)
            },
        ).create(playerId, initialFormat)
    }

    fun readProgress(player: ServerPlayer): Map<TowerBattleFormat, TowerProgress> =
        TowerBattleFormat.entries.associateWith { format -> readProgress(player.server, player.uuid, format) }

    fun readParty(player: ServerPlayer): List<TowerPlayPartySlot> =
        Cobblemon.storage.getParty(player).toGappyList().mapIndexedNotNull { slot, pokemon ->
            pokemon?.let {
                val heldItem = it.heldItem()
                TowerPlayPartySlot(
                    slot = slot,
                    pokemonId = it.uuid,
                    speciesId = it.species.resourceIdentifier.toString(),
                    heldItemId = if (heldItem.isEmpty) null else BuiltInRegistries.ITEM.getKey(heldItem.item).toString(),
                    level = it.level,
                    battleLevel = it.level.coerceAtMost(TOWER_BATTLE_LEVEL_CAP),
                    legendaryClass = TowerLegendaryClassPolicy.isLegendaryClass(
                        it.species.resourceIdentifier.toString(),
                        it.species.labels,
                    ),
                )
            }
        }

    private fun readProgress(
        server: MinecraftServer,
        playerId: UUID,
        format: TowerBattleFormat,
    ) = TowerProgressRecordCodec.decode(
        BattleRecordService.get(
            server,
            BattleRecordKey(
                playerId,
                BattleRecordCategory(TowerRecordContract.CONTENT_ID, format.recordId),
            ),
        ),
    )
}
