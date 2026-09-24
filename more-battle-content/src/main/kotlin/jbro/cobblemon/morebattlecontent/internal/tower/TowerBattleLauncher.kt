package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic

internal data class TowerBattleLaunchRequest(
    val playerId: UUID,
    val progress: TowerProgress,
    val selection: TowerSelectedTeam,
    val playerTeamPreview: BattleOpponentTeamPreviewView,
    val mechanic: MajorBattleMechanic,
    val legendaryClassAllowed: Boolean = false,
    val learningScopeId: UUID = UUID.randomUUID(),
) {
    init {
        require(progress.format == selection.format) {
            "Battle Tower progress and selected team formats must match"
        }
        require(playerTeamPreview.selectionSize == progress.format.selectionSize) {
            "Battle Tower public preview and battle format selection sizes must match"
        }
        require(playerTeamPreview.pokemon.size == BattleOpponentTeamPreviewView.MAX_PREVIEW_SIZE) {
            "Battle Tower requires the complete six-member public player preview"
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
