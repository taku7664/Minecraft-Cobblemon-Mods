package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.client.hub.MccHubLayout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Screens are resized freely; no hub size may make a layout throw and crash the game. */
class LeagueHubSizeSweepTest {
    /** Every hub content area from the smallest screen the hub lays tabs out on up to a large one. */
    private val contentAreas = buildList {
        for (width in MccHubLayout.MIN_WIDTH..900 step 23) for (height in MccHubLayout.MIN_HEIGHT..500 step 17) {
            add(MccHubLayout.calculate(width, height, 7).content)
        }
    }

    @Test
    fun `the League tab lays out in every hub content area`() {
        contentAreas.forEach { LeagueHubLayout.calculate(it) }
    }
}
