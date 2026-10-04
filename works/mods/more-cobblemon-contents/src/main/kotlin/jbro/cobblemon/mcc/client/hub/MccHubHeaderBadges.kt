package jbro.cobblemon.mcc.client.hub

import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import net.minecraft.network.chat.Component

/**
 * A status a content mod shows in the hub header, left of the BP balance: an [icon] drawn by the UI kit and a
 * short [label]. When the header is narrow only the icon stays, and [tooltip] (or the label) names it on hover.
 */
class MccHubHeaderBadge(
    val icon: CobblemonUiRenderContent,
    val label: Component,
    val tooltip: Component? = null,
)

/**
 * Header badges contributed by content mods. Each provider is asked on every frame the header draws, so it
 * returns its current badge, or null while it has nothing to show. Lower [order] sits further left.
 */
object MccHubHeaderBadges {
    private class Entry(val order: Int, val provider: () -> MccHubHeaderBadge?)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(order: Int, provider: () -> MccHubHeaderBadge?): AutoCloseable {
        val entry = Entry(order, provider)
        entries += entry
        return AutoCloseable { entries.remove(entry) }
    }

    fun current(): List<MccHubHeaderBadge> = entries.sortedBy { it.order }.mapNotNull { it.provider() }
}
