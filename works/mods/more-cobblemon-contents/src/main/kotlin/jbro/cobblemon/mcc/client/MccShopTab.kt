package jbro.cobblemon.mcc.client

import java.text.NumberFormat
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.client.hub.MccHubTabContent
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopPurchaseStatus
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopkeeperAppearance
import jbro.cobblemon.mcc.internal.bp.shop.ShopEntryView
import jbro.cobblemon.mcc.internal.bp.shop.ShopPurchasePayload
import jbro.cobblemon.mcc.internal.bp.shop.ShopStatePayload
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiLayoutResult
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/** The shop the client knows and the viewer's cart, fed by the shop state the server sends. */
internal object MccShopClient {
    const val CONTENT: String = BattleHubIds.SHOP

    var state: ShopStatePayload? = null
        private set
    val cart = ShopCart()
    val purchase = PendingClientRequest()
    var itemOffset = 0
    var cartPage = 0
    private var openedByServer = false

    fun accept(next: ShopStatePayload) {
        val previous = state
        if (previous != null && (previous.catalogId != next.catalogId || previous.catalogRevision != next.catalogRevision)) {
            cart.clear()
            itemOffset = 0
        }
        if (next.result == BattlePointShopPurchaseStatus.APPLIED || next.result == BattlePointShopPurchaseStatus.ALREADY_APPLIED) {
            cart.clear()
            cartPage = 0
        }
        cart.retain(next.entries)
        purchase.reset()
        state = next
        MccBattleHubClientState.update(next.balanceBp)
        if (MccHubScreen.showing(CONTENT)) {
            MccHubScreen.refresh(CONTENT)
        } else {
            openedByServer = true
            MccHubScreen.open(CONTENT)
        }
    }

    fun buy() {
        val current = state ?: return
        if (cart.isEmpty) return
        purchase.send {
            ShopPlayClientNetworking.purchase(ShopPurchasePayload(UUID.randomUUID(), current.catalogId, current.catalogRevision, cart.lines()))
        }
    }

    fun clear() {
        state = null
        cart.clear()
        purchase.reset()
        itemOffset = 0
        cartPage = 0
    }

    /** True once after the server opened the hub on the shop, whose fresh state the tab then uses. */
    fun takeOpenedByServer(): Boolean = openedByServer.also { openedByServer = false }
}

/**
 * The BP shop inside the MCC hub, laid out as a counter: the shopkeeper, the catalog, the cart with its totals and
 * the purchase, and the viewer. Goods move left to right from the catalog into the cart.
 */
internal class MccShopTab : MccHubTabContent {
    private var catalog: MccHubKit.Scrollable? = null

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        catalog?.scroll(mouseX, mouseY, scrollY) == true

    override fun shown() {
        if (!MccShopClient.takeOpenedByServer()) MccHubTabs.requestContent(MccShopClient.CONTENT)
    }

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        catalog = null
        val state = MccShopClient.state
        if (state == null) {
            MccHubKit.placeholder(host, bounds, shop("loading"))
            return
        }
        val layout = MccShopLayout.calculate(bounds)
        val keeper = shopkeeper(state.shopkeeper, if (layout.keeper != null) UiModelFraming.FULL_BODY else UiModelFraming.PORTRAIT)
        val viewer = Minecraft.getInstance().let { client -> client.player?.gameProfile ?: client.gameProfile }
        layout.keeper?.let { rect ->
            host.add(CobblemonUiRenderSlot.create(rect.x, rect.y, rect.width, rect.height, UiRenderSlotSpec(shop("shopkeeper")), keeper))
        }
        layout.viewer?.let { rect ->
            host.add(CobblemonUiRenderSlot.create(rect.x, rect.y, rect.width, rect.height, UiRenderSlotSpec(Component.literal(viewer.name)),
                CobblemonUiRenderContent.PlayerProfile(viewer, UiModelFraming.FULL_BODY)))
        }
        addCatalog(host, layout, state, if (layout.keeper == null) keeper else null)
        host.add(Arrow(layout.arrow))
        addCart(host, layout, state, if (layout.viewer == null) CobblemonUiRenderContent.PlayerFace(viewer) else null)
    }

    private fun addCatalog(host: MccHubContentHost, layout: MccShopLayout, state: ShopStatePayload, icon: CobblemonUiRenderContent?) {
        val body = MccHubKit.card(host, layout.catalog, shop("items"), MccHubKit.CardTone.FEATURE, icon)
        val idle = !MccShopClient.purchase.isPending
        if (state.entries.isEmpty()) {
            MccHubKit.placeholder(host, body, shop("empty"))
            return
        }
        catalog = MccHubKit.scrollList(host, body, state.entries.map { entry ->
            MccHubKit.ListEntry(
                itemName(entry),
                trailing = Component.literal(bp(entry.priceBp)),
                enabled = idle && MccShopClient.cart.canAdd(entry, state.limits, state.entries),
                tooltip = shop("add_tooltip", itemName(entry)),
                icon = CobblemonUiRenderContent.Item(itemStack(entry)),
            ) {
                if (MccShopClient.cart.add(entry, state.limits, state.entries)) host.rebuild()
            }
        }, MccShopClient.itemOffset) { MccShopClient.itemOffset = it }
    }

    private fun addCart(host: MccHubContentHost, layout: MccShopLayout, state: ShopStatePayload, icon: CobblemonUiRenderContent?) {
        val cart = MccShopClient.cart
        val body = MccHubKit.card(host, layout.cart,
            shop("cart.title", cart.totalItems(state.entries), state.limits.maxTotalItems), MccHubKit.CardTone.INFO, icon)
        val idle = !MccShopClient.purchase.isPending
        val total = cart.totalCost(state.entries)
        val after = state.balanceBp - total
        val parts = MccShopLayout.cartBody(body, SUMMARY_LINES * 10)
        val button = parts["button"]
        val summary = parts["summary"]
        val list = parts["list"]
        MccHubKit.pagedList(host, list, cart.picked(state.entries).map { (entry, quantity) ->
            MccHubKit.ListEntry(
                itemName(entry),
                trailing = Component.literal("×$quantity"),
                enabled = idle,
                tooltip = shop("line_tooltip", itemName(entry), quantity, bp(entry.priceBp * quantity)),
                icon = CobblemonUiRenderContent.Item(itemStack(entry)),
                actions = listOf(
                    MccHubKit.RowAction(Component.literal("-"), idle) { if (cart.remove(entry.entryId)) host.rebuild() },
                    MccHubKit.RowAction(Component.literal("+"), idle && cart.canAdd(entry, state.limits, state.entries)) {
                        if (cart.add(entry, state.limits, state.entries)) host.rebuild()
                    },
                ),
            ) {}
        }, MccShopClient.cartPage, shop("selection_empty")) { page ->
            MccShopClient.cartPage = page
            host.rebuild()
        }
        host.add(Summary(summary, state.result, total, after))
        val buy = CobblemonUiButton.create(button.x, button.y, button.width,
            UiButtonSpec(shop("purchase"), variant = UiButtonVariant.PRIMARY, size = UiControlSize.MEDIUM,
                width = UiWidthPolicy.Fixed(button.width))) {
            MccShopClient.buy()
            host.rebuild()
        }
        buy.active = idle && !cart.isEmpty && after >= 0 && state.catalogId.isNotEmpty()
        host.add(buy)
    }

    /** The last purchase's outcome, the cart total and what the balance would be after buying it. */
    private class Summary(
        private val rect: UiRect,
        private val result: BattlePointShopPurchaseStatus?,
        private val total: Long,
        private val after: Long,
    ) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val text = MccHubKit.panelText(theme)
            result?.let { status ->
                val good = status == BattlePointShopPurchaseStatus.APPLIED || status == BattlePointShopPurchaseStatus.ALREADY_APPLIED
                graphics.drawString(font, MccHubKit.fitted(shop("result.${status.name.lowercase()}"), rect.width), rect.x, rect.y,
                    if (good) theme.colors.accentGood else theme.colors.accentDanger, false)
            }
            fun line(label: Component, value: String, y: Int, color: Int) {
                graphics.drawString(font, MccHubKit.fitted(label, rect.width - font.width(value) - 6), rect.x, y, text, false)
                graphics.drawString(font, value, rect.right - font.width(value), y, color, false)
            }
            line(shop("total"), bp(total), rect.y + 10, text)
            line(shop("after"), bp(after), rect.y + 20, if (after < 0) theme.colors.accentDanger else text)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** The counter's arrow: goods go from the catalog into the cart. */
    private class Arrow(private val rect: UiRect) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val color = CobblemonUiThemes.registry.snapshot().colors.accentDanger
            val centerY = rect.y + rect.height / 2
            val left = rect.x + 1
            graphics.fill(left, centerY - 2, left + 5, centerY + 2, color)
            (0 until 5).forEach { step ->
                graphics.fill(left + 5 + step, centerY - 5 + step, left + 6 + step, centerY + 5 - step, color)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private companion object {
        /** The purchase result, the total and the balance after. */
        const val SUMMARY_LINES = 3
    }
}

/**
 * The shop counter inside whatever rectangle of the hub content area it is given. Wide hubs put the shopkeeper
 * and the viewer on either side as full-body models; narrow ones keep only the catalog and the cart and move them
 * into the card titles.
 */
internal data class MccShopLayout(
    val keeper: UiRect?,
    val catalog: UiRect,
    val arrow: UiRect,
    val cart: UiRect,
    val viewer: UiRect?,
) {
    companion object {
        const val MODEL_MIN_WIDTH = 420
        private const val MODEL_PERCENT = 17
        private const val ARROW_WIDTH = 12

        fun calculate(bounds: UiRect): MccShopLayout {
            val layout = UiLayout.responsive { size ->
                val withModels = size.width >= MODEL_MIN_WIDTH
                UiLayout.row {
                    if (withModels) {
                        percent(MODEL_PERCENT, "keeper", min = 56, max = 110)
                        space(MccHubKit.GAP)
                    }
                    weight("catalog")
                    fixed(ARROW_WIDTH, "arrow")
                    weight("cart")
                    if (withModels) {
                        space(MccHubKit.GAP)
                        percent(MODEL_PERCENT, "viewer", min = 56, max = 110)
                    }
                }
            }.solve(bounds)
            return MccShopLayout(layout.find("keeper"), layout["catalog"], layout["arrow"], layout["cart"], layout.find("viewer"))
        }

        /** The cart card's body: its lines, then the purchase summary and the buy button at the bottom. */
        fun cartBody(body: UiRect, summaryHeight: Int): UiLayoutResult = UiLayout.column(gap = 4) {
            weight("list", min = 1)
            fixed(summaryHeight, "summary")
            fixed(MccHubKit.CONTROL_HEIGHT, "button")
        }.solve(body)
    }
}

/** The first shopkeeper appearance this client can draw, falling back to Cobblemon's nurse villager. */
internal fun shopkeeper(appearances: List<BattlePointShopkeeperAppearance>, framing: UiModelFraming): CobblemonUiRenderContent {
    val resources = Minecraft.getInstance().resourceManager
    fun present(location: ResourceLocation) = resources.getResource(location).isPresent
    appearances.forEach { appearance ->
        when (appearance) {
            is BattlePointShopkeeperAppearance.Skin -> ResourceLocation.tryParse(appearance.texture)?.takeIf(::present)?.let {
                return CobblemonUiRenderContent.PlayerSkin(UiIcon(it.namespace, it.path), appearance.slim, framing)
            }
            is BattlePointShopkeeperAppearance.Villager -> {
                val profession = ResourceLocation.tryParse(appearance.profession) ?: return@forEach
                val type = ResourceLocation.tryParse(appearance.type) ?: return@forEach
                if (present(ResourceLocation.fromNamespaceAndPath(profession.namespace, "textures/entity/villager/profession/${profession.path}.png"))) {
                    return CobblemonUiRenderContent.Villager(profession, type, framing)
                }
            }
        }
    }
    return CobblemonUiRenderContent.Villager(ResourceLocation.fromNamespaceAndPath("cobblemon", "nurse_joy"), framing = framing)
}

private fun itemStack(entry: ShopEntryView): ItemStack =
    ResourceLocation.tryParse(entry.itemId)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
        ?.let { ItemStack(it, entry.itemCount) } ?: ItemStack.EMPTY

private fun itemName(entry: ShopEntryView): Component {
    val name = itemStack(entry).takeUnless(ItemStack::isEmpty)?.hoverName ?: Component.literal(entry.itemId)
    return if (entry.itemCount > 1) Component.empty().append(name).append(" ×${entry.itemCount}") else name
}

private fun bp(amount: Long): String = "${NumberFormat.getIntegerInstance().format(amount)} BP"

private fun shop(key: String, vararg args: Any): Component =
    Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.shop.$key", *args)
