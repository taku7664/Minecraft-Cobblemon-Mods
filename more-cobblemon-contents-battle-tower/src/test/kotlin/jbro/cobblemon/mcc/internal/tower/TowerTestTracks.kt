package jbro.cobblemon.mcc.internal.tower

/**
 * Every track's progress with [endless] for the Endless tracks and Normal already cleared, so a session opens in
 * Endless the way the Tower ran before Normal existed.
 */
internal fun clearedNormalWith(endless: Map<TowerBattleFormat, TowerProgress>): Map<TowerTrack, TowerProgress> =
    TowerTrack.entries.associateWith { track ->
        if (track.mode == TowerMode.ENDLESS) {
            endless.getValue(track.format)
        } else {
            TowerProgress(track.format, 0, TOWER_NORMAL_CLEAR_WINS, TowerMode.NORMAL)
        }
    }
