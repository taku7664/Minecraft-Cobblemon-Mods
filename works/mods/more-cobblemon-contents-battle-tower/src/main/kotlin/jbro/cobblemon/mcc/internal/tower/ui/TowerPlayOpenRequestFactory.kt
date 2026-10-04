package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerTrack

internal fun interface TowerPartySource {
    fun read(playerId: UUID): List<TowerPlayPartySlot>
}

internal fun interface TowerProgressSource {
    fun read(playerId: UUID, track: TowerTrack): TowerProgress
}

internal fun interface TowerBattlePointSource {
    fun balance(playerId: UUID): Long
}

internal class TowerPlayOpenRequestFactory(
    private val partySource: TowerPartySource,
    private val progressSource: TowerProgressSource,
    private val bpSource: TowerBattlePointSource,
) {
    fun create(playerId: UUID, initialFormat: TowerBattleFormat, initialMode: TowerMode? = null): TowerPlayOpenRequest =
        TowerPlayOpenRequest(
            party = partySource.read(playerId),
            initialFormat = initialFormat,
            progressByTrack = TowerTrack.entries.associateWith { track -> progressSource.read(playerId, track) },
            bpBalance = bpSource.balance(playerId),
            initialMode = initialMode,
        )
}
