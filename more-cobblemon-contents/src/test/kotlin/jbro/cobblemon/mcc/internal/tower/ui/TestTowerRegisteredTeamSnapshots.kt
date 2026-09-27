package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeam
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshots

internal object TestTowerRegisteredTeamSnapshots : TowerRegisteredTeamSnapshots {
    override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult =
        TowerRegisteredTeamSnapshotResult.Stored

    override fun discard(playerId: UUID) = Unit
}
