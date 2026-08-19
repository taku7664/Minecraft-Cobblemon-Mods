package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic

internal data class TowerBattleLaunchRequest(
    val playerId: UUID,
    val progress: TowerProgress,
    val selection: TowerSelectedTeam,
    val mechanic: MajorBattleMechanic,
    val learningScopeId: UUID = UUID.randomUUID(),
) {
    init {
        require(progress.format == selection.format) {
            "Battle Tower progress and selected team formats must match"
        }
    }
}

internal sealed interface TowerBattleLaunchResult {
    data class Started(val battleId: UUID) : TowerBattleLaunchResult
    data object Unavailable : TowerBattleLaunchResult
}

internal fun interface TowerBattleLauncher {
    fun launch(request: TowerBattleLaunchRequest): TowerBattleLaunchResult
}

internal val UnavailableTowerBattleLauncher = TowerBattleLauncher {
    TowerBattleLaunchResult.Unavailable
}
