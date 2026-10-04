package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/**
 * Battle Tower inside whatever rectangle of the hub content area it is given: a progress strip, the party card
 * beside the setup card, and a footer.
 */
internal data class TowerHubLayout(
    val strip: UiRect,
    val party: UiRect,
    val setup: UiRect,
    val footer: UiRect,
) {
    companion object {
        const val PARTY_SIZE = 6

        fun calculate(bounds: UiRect): TowerHubLayout {
            val layout = MccHubKit.tabFrame(UiLayout.row(gap = MccHubKit.GAP) {
                weight("party", 58, min = 1)
                weight("setup", 42, min = 1)
            }).solve(bounds)
            return TowerHubLayout(layout["strip"], layout["party"], layout["setup"], layout["footer"])
        }
    }
}
