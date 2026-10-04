package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.record.BattleRecordCategory
import jbro.cobblemon.mcc.internal.record.BattleRecordKey
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import jbro.cobblemon.mcc.internal.tower.TowerProgressRecordCodec
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerRecordContract
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayOpenRequest
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayOpenRequestFactory
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPartySlot
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.MinecraftServer
import java.util.UUID

internal object Cobblemon173TowerPlayOpenRequestFactory {
    fun create(
        player: ServerPlayer,
        initialFormat: TowerBattleFormat = TowerBattleFormat.SINGLE,
        initialMode: TowerMode? = null,
    ): TowerPlayOpenRequest {
        val server = player.server
        val playerId = player.uuid
        return TowerPlayOpenRequestFactory(
            partySource = { requestedPlayerId ->
                require(requestedPlayerId == playerId) { "Player changed while reading the Battle Tower party" }
                readParty(player)
            },
            progressSource = { requestedPlayerId, track ->
                require(requestedPlayerId == playerId) { "Player changed while reading Battle Tower progress" }
                readProgress(server, playerId, track)
            },
            bpSource = { requestedPlayerId ->
                require(requestedPlayerId == playerId) { "Player changed while reading BP" }
                BattlePointService.balance(server, playerId)
            },
        ).create(playerId, initialFormat, initialMode)
    }

    fun readProgress(player: ServerPlayer): Map<TowerTrack, TowerProgress> =
        TowerTrack.entries.associateWith { track -> readProgress(player.server, player.uuid, track) }

    fun readParty(player: ServerPlayer): List<TowerPlayPartySlot> =
        Cobblemon.storage.getParty(player).toGappyList().mapIndexedNotNull { slot, pokemon ->
            pokemon?.let {
                val registration = it.toTowerPokemonRegistration()
                TowerPlayPartySlot(
                    slot = slot,
                    pokemonId = registration.pokemonId,
                    speciesId = registration.speciesId,
                    heldItemId = registration.heldItemId,
                    level = registration.level,
                    battleLevel = registration.battleLevel,
                    legendaryClass = registration.legendaryClass,
                    formId = registration.formId,
                )
            }
        }

    private fun readProgress(
        server: MinecraftServer,
        playerId: UUID,
        track: TowerTrack,
    ) = TowerProgressRecordCodec.decode(
        BattleRecordService.get(
            server,
            BattleRecordKey(
                playerId,
                BattleRecordCategory(TowerRecordContract.CONTENT_ID, track.recordId),
            ),
        ),
    )
}
