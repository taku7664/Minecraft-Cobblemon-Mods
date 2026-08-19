package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeam
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshots

internal object TestTowerRegisteredTeamSnapshots : TowerRegisteredTeamSnapshots {
    override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult =
        TowerRegisteredTeamSnapshotResult.Stored

    override fun discard(playerId: UUID) = Unit
}
