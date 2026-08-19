package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress

internal fun interface TowerPartySource {
    fun read(playerId: UUID): List<TowerPlayPartySlot>
}

internal fun interface TowerProgressSource {
    fun read(playerId: UUID, format: TowerBattleFormat): TowerProgress
}

internal fun interface TowerBattlePointSource {
    fun balance(playerId: UUID): Long
}

internal class TowerPlayOpenRequestFactory(
    private val partySource: TowerPartySource,
    private val progressSource: TowerProgressSource,
    private val bpSource: TowerBattlePointSource,
) {
    fun create(playerId: UUID, initialFormat: TowerBattleFormat): TowerPlayOpenRequest =
        TowerPlayOpenRequest(
            party = partySource.read(playerId),
            initialFormat = initialFormat,
            progressByFormat = TowerBattleFormat.entries.associateWith { format ->
                progressSource.read(playerId, format)
            },
            bpBalance = bpSource.balance(playerId),
        )
}
