package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
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
            val gap = MccHubKit.GAP
            val strip = UiRect(bounds.x, bounds.y, bounds.width, MccHubKit.STRIP_HEIGHT)
            val footer = UiRect(bounds.x, bounds.bottom - MccHubKit.FOOTER_HEIGHT, bounds.width, MccHubKit.FOOTER_HEIGHT)
            val body = UiRect(bounds.x, strip.bottom + gap, bounds.width, (footer.y - gap - strip.bottom - gap).coerceAtLeast(1))
            val (party, setup) = MccHubKit.columns(body, 58, 42)
            return TowerHubLayout(strip, party, setup, footer)
        }
    }
}
