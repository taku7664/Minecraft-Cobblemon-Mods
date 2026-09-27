package jbro.cobblemon.mcc.client.hub

import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.hub.BattleHubOpenContentPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/** Where a hub tab draws: inside the hub's content area, or in a screen of its own. */
sealed interface MccHubTabKind {
    /** The tab's widgets live inside the hub screen. */
    class Embedded(val create: () -> MccHubTabContent) : MccHubTabKind

    /** The tab still opens its own screen (content not yet rebuilt on the hub). */
    class Screen(val open: () -> Unit) : MccHubTabKind
}

/**
 * One entry in the hub's left rail. Lower [order] comes first: the core uses 0 for the dashboard and
 * 10 for the shop; content mods use 90 and up in module order. [accessContentId] is matched against
 * the server's hub denials so a locked content shows why it is locked.
 */
class MccHubTab(
    val id: String,
    val label: Component,
    val order: Int,
    val kind: MccHubTabKind,
    val accessContentId: String? = id,
)

/** What an embedded tab needs from the hub screen. */
interface MccHubContentHost {
    fun <T : AbstractWidget> add(widget: T): T

    /** Rebuilds the whole hub, for example after new server state arrived. */
    fun rebuild()
}

/**
 * An embedded tab. The hub creates it the first time the tab is selected and keeps it while the hub stays
 * open; [build] runs again whenever the hub rebuilds.
 */
interface MccHubTabContent {
    fun build(host: MccHubContentHost, bounds: UiRect)

    /** The tab became the visible one, including the hub coming back after a dialog it opened. */
    fun shown() = Unit

    /** Another tab took over, or the hub stopped being the screen. */
    fun hidden() = Unit

    fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean = false
}

object MccHubTabs {
    const val DASHBOARD: String = "${MoreCobblemonContents.MOD_ID}:dashboard"

    private val tabs = CopyOnWriteArrayList<MccHubTab>()

    fun register(tab: MccHubTab): AutoCloseable {
        require(tabs.none { it.id == tab.id }) { "Duplicate hub tab: ${tab.id}" }
        tabs += tab
        return AutoCloseable { tabs.remove(tab) }
    }

    fun all(): List<MccHubTab> = tabs.sortedWith(compareBy({ it.order }, { it.id }))

    fun get(id: String): MccHubTab? = tabs.firstOrNull { it.id == id }

    /** Asks the server to open a content registered with the server-side hub, as screen tabs do. */
    fun requestContent(contentId: String) {
        ClientPlayNetworking.send(BattleHubOpenContentPayload(contentId))
    }
}
