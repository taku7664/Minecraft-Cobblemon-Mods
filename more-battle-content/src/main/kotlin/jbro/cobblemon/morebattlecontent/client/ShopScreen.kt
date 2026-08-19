package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import kotlin.math.roundToInt
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopPurchaseStatus
import jbro.cobblemon.morebattlecontent.internal.bp.shop.HomeLeaderboardCatalogPayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.HomeLeaderboardEntry
import jbro.cobblemon.morebattlecontent.internal.bp.shop.HomeLeaderboardStatePayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopEntryView
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopPurchasePayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopStatePayload
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubContent
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal class ShopScreen(
    initialState: ShopStatePayload?,
    initialLeaderboard: HomeLeaderboardStatePayload?,
    initialLeaderboardCatalog: HomeLeaderboardCatalogPayload? = null,
) : MbcTabbedContentScreen(Component.translatable(homeKey("title")), BattleHubContent.SHOP) {
    private var state = initialState
    private var leaderboard = initialLeaderboard
    private var leaderboardCatalog = initialLeaderboardCatalog
    private val purchaseSelection = ShopPurchaseSelection()
    private val itemButtons = mutableListOf<ShopItemButton>()
    private val playerModelRenderer = PvpRoomPlayerModelRenderer()
    private var leaderboardFormat = TowerBattleFormat.SINGLE
    private var leaderboardContent = LeaderboardContent.TOWER
    private var leaderboardFactoryLevel = FactoryLevelMode.LEVEL_50
    private var shopScrollOffset = 0
    private var leaderboardScrollOffset = 0
    private var dragTarget: ScrollTarget? = null
    private var dragGrabOffset = 0
    private var pending = false
    private var resultFeedback = initialState?.result

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val frame = frameLayout()
        val layout = ShopScreenLayout.calculate(frame.content)
        drawContentFrame(graphics, frame)
        MbcGuiSurface.drawPanel(graphics, layout.characterPanel, MbcGuiPalette.ACCENT_PRIMARY)
        MbcGuiSurface.drawPanel(graphics, layout.leaderboardPanel, MbcGuiPalette.ACCENT_SECONDARY, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.shopPanel, MbcGuiPalette.ACCENT_BP)
        drawCharacter(graphics, layout)
        drawLeaderboard(graphics, layout)
        drawShop(graphics, layout)
        drawScrollBar(graphics, layout.shopScrollTrack, shopMetrics(layout), shopScrollOffset, MbcGuiPalette.ACCENT_BP)
        drawScrollBar(
            graphics,
            layout.leaderboardScrollTrack,
            leaderboardMetrics(layout),
            leaderboardScrollOffset,
            MbcGuiPalette.ACCENT_SECONDARY,
        )
        super.render(graphics, mouseX, mouseY, partialTick)

        itemButtons.firstOrNull { it.isMouseOver(mouseX.toDouble(), mouseY.toDouble()) }?.let { button ->
            graphics.renderTooltip(font, button.stack, mouseX, mouseY)
        }
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val layout = ShopScreenLayout.calculate(frameLayout().content)
        val delta = if (scrollY != 0.0) scrollY else scrollX
        if (layout.shopViewport.contains(mouseX, mouseY)) {
            val metrics = shopMetrics(layout)
            val next = metrics.afterWheel(shopScrollOffset, delta)
            if (next != shopScrollOffset) updateShopScroll(next, layout, metrics)
            return true
        }
        if (layout.leaderboardViewport.contains(mouseX, mouseY)) {
            val metrics = leaderboardMetrics(layout)
            val next = metrics.afterWheel(leaderboardScrollOffset, delta)
            if (next != leaderboardScrollOffset) updateLeaderboardScroll(next, metrics)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val layout = ShopScreenLayout.calculate(frameLayout().content)
            if (beginScrollDrag(ScrollTarget.SHOP, layout.shopScrollTrack, shopMetrics(layout), shopScrollOffset, mouseX, mouseY)) {
                return true
            }
            if (beginScrollDrag(
                    ScrollTarget.LEADERBOARD,
                    layout.leaderboardScrollTrack,
                    leaderboardMetrics(layout),
                    leaderboardScrollOffset,
                    mouseX,
                    mouseY,
                )
            ) {
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        if (button == 0) {
            val layout = ShopScreenLayout.calculate(frameLayout().content)
            when (dragTarget) {
                ScrollTarget.SHOP -> {
                    val metrics = shopMetrics(layout)
                    updateShopScroll(
                        metrics.offsetForThumbTop(layout.shopScrollTrack.top, mouseY.roundToInt() - dragGrabOffset),
                        layout,
                        metrics,
                    )
                    return true
                }
                ScrollTarget.LEADERBOARD -> {
                    val metrics = leaderboardMetrics(layout)
                    updateLeaderboardScroll(
                        metrics.offsetForThumbTop(
                            layout.leaderboardScrollTrack.top,
                            mouseY.roundToInt() - dragGrabOffset,
                        ),
                        metrics,
                    )
                    return true
                }
                null -> Unit
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && dragTarget != null) {
            dragTarget = null
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    fun applyState(payload: ShopStatePayload) {
        val previous = state
        if (previous != null &&
            (previous.catalogId != payload.catalogId || previous.catalogRevision != payload.catalogRevision)
        ) {
            purchaseSelection.reset()
            shopScrollOffset = 0
        }
        if (payload.result == BattlePointShopPurchaseStatus.APPLIED ||
            payload.result == BattlePointShopPurchaseStatus.ALREADY_APPLIED
        ) {
            purchaseSelection.resetQuantity()
        }
        state = payload
        purchaseSelection.retain(payload.entries.mapTo(hashSetOf(), ShopEntryView::entryId))
        pending = false
        resultFeedback = payload.result
        MbcBattleHubClientState.update(payload.balanceBp)
        val layout = ShopScreenLayout.calculate(frameLayout().content)
        shopScrollOffset = shopScrollOffset.coerceIn(0, shopMetrics(layout).maxOffset)
        rebuild()
    }

    fun applyLeaderboard(payload: HomeLeaderboardStatePayload) {
        leaderboard = payload
        val metrics = leaderboardMetrics(ShopScreenLayout.calculate(frameLayout().content))
        leaderboardScrollOffset = leaderboardScrollOffset.coerceIn(0, metrics.maxOffset)
        rebuild()
    }

    fun applyLeaderboardCatalog(payload: HomeLeaderboardCatalogPayload) {
        leaderboardCatalog = payload
        val metrics = leaderboardMetrics(ShopScreenLayout.calculate(frameLayout().content))
        leaderboardScrollOffset = leaderboardScrollOffset.coerceIn(0, metrics.maxOffset)
        rebuild()
    }

    private fun buildWidgets() {
        itemButtons.clear()
        val frame = frameLayout()
        val layout = ShopScreenLayout.calculate(frame.content)
        addContentFrameWidgets(frame)
        LeaderboardContent.entries.forEachIndexed { index, content ->
            addRenderableWidget(
                MbcStyledButton(
                    layout.leaderboardContentButtons[index],
                    Component.translatable(homeKey("leaderboard.content.${content.translationId}")),
                    MbcButtonTone.SECONDARY,
                    selected = leaderboardContent == content,
                ) { changeLeaderboardContent(content) },
            )
        }
        TowerBattleFormat.entries.forEachIndexed { index, format ->
            addRenderableWidget(
                MbcStyledButton(
                    layout.leaderboardFormatButtons[index],
                    Component.translatable(homeKey("leaderboard.${format.recordId}")),
                    MbcButtonTone.SECONDARY,
                    selected = leaderboardFormat == format,
                ) { changeLeaderboardFormat(format) },
            )
        }
        if (leaderboardContent == LeaderboardContent.FACTORY) {
            FactoryLevelMode.entries.forEachIndexed { index, levelMode ->
                addRenderableWidget(
                    MbcStyledButton(
                        layout.leaderboardLevelButtons[index],
                        Component.translatable(homeKey("leaderboard.level.${levelMode.id}")),
                        MbcButtonTone.SECONDARY,
                        selected = leaderboardFactoryLevel == levelMode,
                    ) { changeLeaderboardFactoryLevel(levelMode) },
                )
            }
        }
        val current = state
        if (current != null) {
            val metrics = shopMetrics(layout)
            shopScrollOffset = shopScrollOffset.coerceIn(0, metrics.maxOffset)
            current.entries.forEachIndexed { index, entry ->
                val button = ShopItemButton(
                    layout.shopCardBounds(index, shopScrollOffset),
                    layout.shopViewport,
                    entry,
                    entry.itemStack(),
                    purchaseSelection.entryId == entry.entryId,
                ) { selectEntry(entry) }
                button.reposition(layout.shopCardBounds(index, shopScrollOffset), layout.shopViewport)
                itemButtons += button
                addRenderableWidget(button)
            }
        }
        addRenderableWidget(
            MbcStyledButton(layout.decrement, Component.literal("−"), MbcButtonTone.NEUTRAL) { changeSelected(-1) }
                .also { it.active = purchaseSelection.quantity > 1 && !pending },
        )
        addRenderableWidget(
            MbcStyledButton(layout.increment, Component.literal("+"), MbcButtonTone.PRIMARY) { changeSelected(1) }
                .also { it.active = canIncrement() && !pending },
        )
        addRenderableWidget(
            MbcStyledButton(layout.purchase, Component.translatable(shopKey("purchase")), MbcButtonTone.SECONDARY) {
                state?.let(::purchase)
            }.also { it.active = current?.let(::canPurchase) == true && !pending },
        )
    }

    private fun drawCharacter(graphics: GuiGraphics, layout: ShopScreenLayout) {
        val player = minecraft?.player ?: return
        val viewport = layout.characterViewport
        val placement = HomePlayerModelPlacement.calculate(viewport, player.bbHeight)
        graphics.enableScissor(viewport.left, viewport.top, viewport.right, viewport.bottom)
        playerModelRenderer.retain(setOf(player.uuid))
        playerModelRenderer.render(
            graphics,
            player.uuid,
            player.gameProfile,
            placement.centerX,
            placement.centerY,
            placement.scale,
        )
        graphics.disableScissor()
        val name = font.plainSubstrByWidth(player.scoreboardName, (layout.characterPanel.width - 12).coerceAtLeast(1))
        graphics.drawCenteredString(
            font,
            name,
            layout.characterPanel.left + layout.characterPanel.width / 2,
            layout.characterPanel.bottom - 12,
            MbcGuiPalette.TEXT_PRIMARY,
        )
    }

    private fun drawLeaderboard(graphics: GuiGraphics, layout: ShopScreenLayout) {
        graphics.drawString(
            font,
            Component.translatable(homeKey("leaderboard")),
            layout.leaderboardPanel.left + 6,
            layout.leaderboardPanel.top + 5,
            MbcGuiPalette.ACCENT_SECONDARY,
            false,
        )
        if (leaderboardContent != LeaderboardContent.FACTORY) {
            graphics.drawCenteredString(
                font,
                Component.translatable(homeKey("leaderboard.sort.${leaderboardContent.translationId}")),
                layout.leaderboardPanel.left + layout.leaderboardPanel.width / 2,
                layout.leaderboardLevelButtons.first().top + 4,
                MbcGuiPalette.TEXT_DIM,
            )
        }
        val entries = currentLeaderboardEntries()
        if (entries == null) {
            graphics.drawCenteredString(
                font,
                Component.translatable(homeKey("leaderboard.loading")),
                layout.leaderboardViewport.left + layout.leaderboardViewport.width / 2,
                layout.leaderboardViewport.top + 5,
                MbcGuiPalette.TEXT_DIM,
            )
            return
        }
        if (entries.isEmpty()) {
            graphics.drawCenteredString(
                font,
                Component.translatable(homeKey("leaderboard.empty")),
                layout.leaderboardViewport.left + layout.leaderboardViewport.width / 2,
                layout.leaderboardViewport.top + 5,
                MbcGuiPalette.TEXT_DIM,
            )
            return
        }
        val ownId = minecraft?.player?.uuid
        graphics.enableScissor(
            layout.leaderboardViewport.left,
            layout.leaderboardViewport.top,
            layout.leaderboardViewport.right,
            layout.leaderboardViewport.bottom,
        )
        entries.forEachIndexed { index, entry ->
            val row = layout.leaderboardRowBounds(index, leaderboardScrollOffset)
            if (!row.overlaps(layout.leaderboardViewport)) return@forEachIndexed
            val own = entry.playerId == ownId
            if (own || index % 2 == 1) {
                graphics.fill(
                    row.left,
                    row.top,
                    row.right,
                    row.bottom,
                    if (own) MbcGuiPalette.BUTTON_SELECTED else MbcGuiPalette.BUTTON_DISABLED,
                )
            }
            val rankWidth = 24
            graphics.drawString(
                font,
                "#${entry.place}",
                row.left + 3,
                row.top + 3,
                if (own) MbcGuiPalette.ACCENT_PRIMARY else MbcGuiPalette.ACCENT_SECONDARY,
                false,
            )
            val detail = leaderboardDetail(entry, row.width < 250)
            val detailWidth = font.width(detail)
            val availableNameWidth = (row.width - rankWidth - detailWidth - 10).coerceAtLeast(1)
            val name = font.plainSubstrByWidth(entry.playerName, availableNameWidth)
            graphics.drawString(font, name, row.left + rankWidth, row.top + 3, MbcGuiPalette.TEXT_PRIMARY, false)
            graphics.drawString(
                font,
                detail,
                (row.right - detailWidth - 3).coerceAtLeast(row.left + rankWidth),
                row.top + 3,
                MbcGuiPalette.TEXT_SECONDARY,
                false,
            )
        }
        graphics.disableScissor()
    }

    private fun drawShop(graphics: GuiGraphics, layout: ShopScreenLayout) {
        MbcGuiSurface.drawBadge(graphics, layout.shopBalanceBadge, MbcGuiPalette.ACCENT_BP)
        graphics.drawCenteredString(
            font,
            Component.literal("${MbcBattleHubClientState.bpBalance} BP"),
            layout.shopBalanceBadge.left + layout.shopBalanceBadge.width / 2,
            layout.shopBalanceBadge.top + 2,
            MbcGuiPalette.ACCENT_BP,
        )
        graphics.drawString(
            font,
            Component.translatable(shopKey("catalog")),
            layout.shopPanel.left + 6,
            layout.shopPanel.top + 5,
            MbcGuiPalette.ACCENT_BP,
            false,
        )
        if (state == null) {
            graphics.drawCenteredString(
                font,
                Component.translatable(shopKey("loading")),
                layout.shopViewport.left + layout.shopViewport.width / 2,
                layout.shopViewport.top + 6,
                MbcGuiPalette.TEXT_SECONDARY,
            )
        }
        val result = resultFeedback
        val summary = if (result != null) {
            Component.translatable(shopKey("result.${result.name.lowercase()}"))
        } else {
            if (purchaseSelection.entryId == null) {
                Component.translatable(shopKey("selection_empty"))
            } else {
                Component.translatable(shopKey("selection_summary"), totalItemCount(), totalCost())
            }
        }
        val color = when (result) {
            BattlePointShopPurchaseStatus.APPLIED,
            BattlePointShopPurchaseStatus.ALREADY_APPLIED,
            -> MbcGuiPalette.ACCENT_GOOD
            null -> MbcGuiPalette.TEXT_PRIMARY
            else -> MbcGuiPalette.ACCENT_DANGER
        }
        val summaryTop = layout.decrement.top - 11
        graphics.drawString(
            font,
            font.plainSubstrByWidth(summary.string, (layout.shopPanel.width - 12).coerceAtLeast(1)),
            layout.shopPanel.left + 6,
            summaryTop,
            color,
            false,
        )
        purchaseSelection.entryId?.let { id ->
            val quantity = purchaseSelection.quantity
            val label = state?.entries?.firstOrNull { it.entryId == id }?.itemStack()?.hoverName?.string.orEmpty()
            val text = font.plainSubstrByWidth("$label ×$quantity", (layout.purchase.right - layout.increment.right - 8).coerceAtLeast(1))
            graphics.drawString(
                font,
                text,
                layout.increment.right + 5,
                layout.increment.top + 4,
                MbcGuiPalette.TEXT_SECONDARY,
                false,
            )
        }
    }

    private fun changeLeaderboardFormat(format: TowerBattleFormat) {
        if (leaderboardFormat == format) return
        leaderboardFormat = format
        leaderboardScrollOffset = 0
        rebuild()
    }

    private fun changeLeaderboardContent(content: LeaderboardContent) {
        if (leaderboardContent == content) return
        leaderboardContent = content
        leaderboardScrollOffset = 0
        rebuild()
    }

    private fun changeLeaderboardFactoryLevel(levelMode: FactoryLevelMode) {
        if (leaderboardFactoryLevel == levelMode) return
        leaderboardFactoryLevel = levelMode
        leaderboardScrollOffset = 0
        rebuild()
    }

    private fun currentLeaderboardEntries(): List<HomeLeaderboardEntry>? {
        val formatId = when (leaderboardContent) {
            LeaderboardContent.FACTORY -> "${leaderboardFormat.recordId}_${leaderboardFactoryLevel.id}"
            LeaderboardContent.TOWER,
            LeaderboardContent.PVP,
            -> leaderboardFormat.recordId
        }
        leaderboardCatalog?.boards
            ?.firstOrNull { it.contentId == leaderboardContent.contentId && it.formatId == formatId }
            ?.let { return it.entries }
        if (leaderboardContent != LeaderboardContent.TOWER) return null
        return when (leaderboardFormat) {
            TowerBattleFormat.SINGLE -> leaderboard?.singles
            TowerBattleFormat.DOUBLE -> leaderboard?.doubles
        }
    }

    private fun leaderboardDetail(entry: HomeLeaderboardEntry, compact: Boolean): String = when (leaderboardContent) {
        LeaderboardContent.TOWER -> Component.translatable(
            homeKey(if (compact) "leaderboard.detail.tower.compact" else "leaderboard.detail.tower"),
            rankLabel(entry.highestRank),
            entry.rankProgress,
            entry.totalWins,
        ).string
        LeaderboardContent.FACTORY -> Component.translatable(
            homeKey(if (compact) "leaderboard.detail.factory.compact" else "leaderboard.detail.factory"),
            entry.highestFloor,
            entry.totalWins,
            entry.totalLosses,
        ).string
        LeaderboardContent.PVP -> Component.translatable(
            homeKey(if (compact) "leaderboard.detail.pvp.compact" else "leaderboard.detail.pvp"),
            entry.totalWins,
            entry.totalLosses,
            entry.bestWinStreak,
        ).string
    }

    private fun rankLabel(order: Long): String {
        val rank = TowerRank.entries.singleOrNull { it.leaderboardOrder == order } ?: return order.toString()
        return if (rank == TowerRank.MAX) "MAX" else rank.serializedId
    }

    private fun selectEntry(entry: ShopEntryView) {
        purchaseSelection.select(entry.entryId)
        resultFeedback = null
        rebuild()
    }

    private fun changeSelected(delta: Int) {
        val current = state ?: return
        val entryId = purchaseSelection.entryId ?: return
        val entry = current.entries.firstOrNull { it.entryId == entryId } ?: return
        if (purchaseSelection.change(
                delta,
                maxQuantity = current.limits.maxQuantityPerLine,
                itemCount = entry.itemCount,
                maxTotalItems = current.limits.maxTotalItems,
            )
        ) {
            resultFeedback = null
            rebuild()
        }
    }

    private fun canIncrement(): Boolean {
        val current = state ?: return false
        val entry = current.entries.firstOrNull { it.entryId == purchaseSelection.entryId } ?: return false
        return purchaseSelection.quantity < current.limits.maxQuantityPerLine &&
            (purchaseSelection.quantity + 1L) * entry.itemCount <= current.limits.maxTotalItems.toLong()
    }

    private fun purchase(current: ShopStatePayload) {
        val lines = purchaseSelection.lines()
        if (lines.isEmpty() || pending || !canPurchase(current)) return
        pending = true
        resultFeedback = null
        ShopPlayClientNetworking.purchase(
            ShopPurchasePayload(
                purchaseId = UUID.randomUUID(),
                catalogId = current.catalogId,
                catalogRevision = current.catalogRevision,
                lines = lines,
            ),
        )
        rebuild()
    }

    private fun canPurchase(current: ShopStatePayload): Boolean {
        val entry = current.entries.firstOrNull { it.entryId == purchaseSelection.entryId } ?: return false
        return purchaseSelection.quantity in 1..current.limits.maxQuantityPerLine &&
            purchaseSelection.totalItems(entry.itemCount) <= current.limits.maxTotalItems &&
            purchaseSelection.totalCost(entry.priceBp) <= current.balanceBp
    }

    private fun totalCost(): Long {
        val entry = state?.entries?.firstOrNull { it.entryId == purchaseSelection.entryId } ?: return 0L
        return purchaseSelection.totalCost(entry.priceBp)
    }

    private fun totalItemCount(): Int {
        val entry = state?.entries?.firstOrNull { it.entryId == purchaseSelection.entryId } ?: return 0
        return purchaseSelection.totalItems(entry.itemCount)
    }

    private fun shopMetrics(layout: ShopScreenLayout): MbcVerticalScrollMetrics =
        MbcVerticalScrollMetrics.calculate(
            viewportHeight = layout.shopViewport.height,
            contentHeight = layout.shopContentHeight(state?.entries?.size ?: 0),
            trackHeight = layout.shopScrollTrack.height,
        )

    private fun leaderboardMetrics(layout: ShopScreenLayout): MbcVerticalScrollMetrics =
        MbcVerticalScrollMetrics.calculate(
            viewportHeight = layout.leaderboardViewport.height,
            contentHeight = layout.leaderboardContentHeight(currentLeaderboardEntries()?.size ?: 0),
            trackHeight = layout.leaderboardScrollTrack.height,
        )

    private fun updateShopScroll(
        nextOffset: Int,
        layout: ShopScreenLayout,
        metrics: MbcVerticalScrollMetrics,
    ) {
        shopScrollOffset = nextOffset.coerceIn(0, metrics.maxOffset)
        itemButtons.forEachIndexed { index, button ->
            button.reposition(layout.shopCardBounds(index, shopScrollOffset), layout.shopViewport)
        }
    }

    private fun updateLeaderboardScroll(nextOffset: Int, metrics: MbcVerticalScrollMetrics) {
        leaderboardScrollOffset = nextOffset.coerceIn(0, metrics.maxOffset)
    }

    private fun beginScrollDrag(
        target: ScrollTarget,
        track: TowerPlayRect,
        metrics: MbcVerticalScrollMetrics,
        offset: Int,
        mouseX: Double,
        mouseY: Double,
    ): Boolean {
        if (!track.contains(mouseX, mouseY) || metrics.maxOffset == 0) return false
        val thumbTop = metrics.thumbTop(track.top, offset)
        val insideThumb = mouseY >= thumbTop && mouseY < thumbTop + metrics.thumbHeight
        dragTarget = target
        dragGrabOffset = if (insideThumb) (mouseY - thumbTop).roundToInt() else metrics.thumbHeight / 2
        val requested = metrics.offsetForThumbTop(track.top, mouseY.roundToInt() - dragGrabOffset)
        val layout = ShopScreenLayout.calculate(frameLayout().content)
        when (target) {
            ScrollTarget.SHOP -> updateShopScroll(requested, layout, metrics)
            ScrollTarget.LEADERBOARD -> updateLeaderboardScroll(requested, metrics)
        }
        return true
    }

    private fun drawScrollBar(
        graphics: GuiGraphics,
        track: TowerPlayRect,
        metrics: MbcVerticalScrollMetrics,
        offset: Int,
        accent: Int,
    ) {
        graphics.fill(track.left, track.top, track.right, track.bottom, MbcGuiPalette.BORDER)
        graphics.fill(track.left + 1, track.top + 1, track.right - 1, track.bottom - 1, MbcGuiPalette.BUTTON_DISABLED)
        val thumbTop = metrics.thumbTop(track.top, offset)
        graphics.fill(
            track.left + 1,
            thumbTop,
            track.right - 1,
            thumbTop + metrics.thumbHeight,
            if (metrics.maxOffset == 0) MbcGuiPalette.BORDER else accent,
        )
    }

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private enum class ScrollTarget { SHOP, LEADERBOARD }

    private enum class LeaderboardContent(val contentId: String, val translationId: String) {
        TOWER("battle_tower", "tower"),
        FACTORY("battle_factory", "factory"),
        PVP("pvp", "pvp"),
    }
}

private class ShopItemButton(
    bounds: TowerPlayRect,
    private var clipBounds: TowerPlayRect,
    private val entry: ShopEntryView,
    val stack: ItemStack,
    private val selected: Boolean,
    private val press: () -> Unit,
) : AbstractButton(bounds.left, bounds.top, bounds.width, bounds.height, stack.hoverName) {
    override fun onPress() = press()

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (!bounds().overlaps(clipBounds)) return
        graphics.enableScissor(clipBounds.left, clipBounds.top, clipBounds.right, clipBounds.bottom)
        MbcGuiSurface.drawButton(
            graphics,
            TowerPlayRect(x, y, width, height),
            active,
            isHoveredOrFocused,
            selected,
            MbcGuiPalette.ACCENT_BP,
        )
        graphics.renderItem(stack, x + 4, y + (height - 16) / 2)
        graphics.renderItemDecorations(Minecraft.getInstance().font, stack, x + 4, y + (height - 16) / 2)
        val font = Minecraft.getInstance().font
        val name = font.plainSubstrByWidth(stack.hoverName.string, (width - 26).coerceAtLeast(1))
        graphics.drawString(font, name, x + 23, y + 5, MbcGuiPalette.TEXT_PRIMARY, false)
        graphics.drawString(font, "${entry.priceBp} BP", x + 23, y + height - 11, MbcGuiPalette.ACCENT_BP, false)
        graphics.disableScissor()
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        clipBounds.contains(mouseX, mouseY) && super.isMouseOver(mouseX, mouseY)

    fun reposition(bounds: TowerPlayRect, clipBounds: TowerPlayRect) {
        x = bounds.left
        y = bounds.top
        width = bounds.width
        this.clipBounds = clipBounds
        visible = bounds.overlaps(clipBounds)
    }

    private fun bounds() = TowerPlayRect(x, y, width, height)

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}

private fun TowerPlayRect.contains(x: Double, y: Double): Boolean =
    x >= left && x < right && y >= top && y < bottom

private fun TowerPlayRect.overlaps(other: TowerPlayRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

private fun ShopEntryView.itemStack(): ItemStack {
    val id = ResourceLocation.tryParse(itemId)
    val item = id?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.BARRIER
    return ItemStack(item, itemCount)
}

private fun homeKey(suffix: String) = "screen.${MoreBattleContent.MOD_ID}.home.$suffix"
private fun shopKey(suffix: String) = "screen.${MoreBattleContent.MOD_ID}.shop.$suffix"
