package jbro.cobblemon.mcc.client

import kotlin.math.roundToInt

internal data class ShopScreenLayout(
    val shell: MccRect,
    val characterPanel: MccRect,
    val leaderboardPanel: MccRect,
    val shopPanel: MccRect,
    val characterViewport: MccRect,
    val leaderboardViewport: MccRect,
    val leaderboardScrollTrack: MccRect,
    val leaderboardContentButtons: List<MccRect>,
    val leaderboardFormatButtons: List<MccRect>,
    val leaderboardLevelButtons: List<MccRect>,
    val shopBalanceBadge: MccRect,
    val shopViewport: MccRect,
    val shopScrollTrack: MccRect,
    val decrement: MccRect,
    val increment: MccRect,
    val purchase: MccRect,
) {
    fun shopContentHeight(entryCount: Int): Int =
        (entryCount.coerceAtLeast(1) * (SHOP_CARD_HEIGHT + ITEM_GAP) - ITEM_GAP).coerceAtLeast(shopViewport.height)

    fun shopCardBounds(index: Int, scrollOffset: Int): MccRect {
        require(index >= 0)
        return MccRect(
            shopViewport.left,
            shopViewport.top + index * (SHOP_CARD_HEIGHT + ITEM_GAP) - scrollOffset,
            shopViewport.width,
            SHOP_CARD_HEIGHT,
        )
    }

    fun leaderboardContentHeight(entryCount: Int): Int =
        (entryCount.coerceAtLeast(1) * LEADERBOARD_ROW_HEIGHT).coerceAtLeast(leaderboardViewport.height)

    fun leaderboardRowBounds(index: Int, scrollOffset: Int): MccRect {
        require(index >= 0)
        return MccRect(
            leaderboardViewport.left,
            leaderboardViewport.top + index * LEADERBOARD_ROW_HEIGHT - scrollOffset,
            leaderboardViewport.width,
            LEADERBOARD_ROW_HEIGHT,
        )
    }

    companion object {
        fun calculate(shell: MccRect): ShopScreenLayout {
            val inner = shell.inset(PANEL_GAP)
            val columnSpace = inner.width - PANEL_GAP * 2
            val desiredLeaderboardWidth =
                (inner.width * LEADERBOARD_WIDTH_PERCENT / 100).coerceAtLeast(MIN_LEADERBOARD_WIDTH)
            val leaderboardWidth = desiredLeaderboardWidth.coerceAtMost(
                (columnSpace - MIN_CHARACTER_WIDTH - MIN_SHOP_WIDTH).coerceAtLeast(1),
            )
            val desiredCharacterWidth =
                (leaderboardWidth * CHARACTER_TO_LEADERBOARD_NUMERATOR / CHARACTER_TO_LEADERBOARD_DENOMINATOR)
                    .coerceAtLeast(MIN_CHARACTER_WIDTH)
            val characterWidth = desiredCharacterWidth.coerceAtMost(
                (columnSpace - leaderboardWidth - MIN_SHOP_WIDTH).coerceAtLeast(1),
            )
            val shopWidth = columnSpace - characterWidth - leaderboardWidth
            val character = MccRect(inner.left, inner.top, characterWidth, inner.height)
            val leaderboard = MccRect(character.right + PANEL_GAP, inner.top, leaderboardWidth, inner.height)
            val shop = MccRect(leaderboard.right + PANEL_GAP, inner.top, shopWidth, inner.height)

            val leaderboardContentRow = MccRect(
                leaderboard.left + PANEL_INSET,
                leaderboard.top + TITLE_HEIGHT,
                leaderboard.width - PANEL_INSET * 2,
                SMALL_CONTROL_HEIGHT,
            )
            val leaderboardContentButtons = partition(leaderboardContentRow, 3)
            val leaderboardFormatRow = MccRect(
                leaderboardContentRow.left,
                leaderboardContentRow.bottom + PANEL_GAP,
                leaderboardContentRow.width,
                SMALL_CONTROL_HEIGHT,
            )
            val leaderboardFormatButtons = partition(leaderboardFormatRow, 2)
            val leaderboardLevelRow = MccRect(
                leaderboardContentRow.left,
                leaderboardFormatRow.bottom + PANEL_GAP,
                leaderboardContentRow.width,
                SMALL_CONTROL_HEIGHT,
            )
            val leaderboardLevelButtons = partition(leaderboardLevelRow, 2)
            val leaderboardTrack = MccRect(
                leaderboard.right - PANEL_INSET - SCROLL_TRACK_WIDTH,
                leaderboardLevelRow.bottom + PANEL_GAP,
                SCROLL_TRACK_WIDTH,
                leaderboard.bottom - PANEL_INSET - leaderboardLevelRow.bottom - PANEL_GAP,
            )
            val leaderboardViewport = MccRect(
                leaderboard.left + PANEL_INSET,
                leaderboardTrack.top,
                leaderboardTrack.left - PANEL_GAP - leaderboard.left - PANEL_INSET,
                leaderboardTrack.height,
            )
            val characterViewport = MccRect(
                character.left + PANEL_INSET,
                character.top + PANEL_INSET,
                character.width - PANEL_INSET * 2,
                character.height - CHARACTER_NAME_HEIGHT - PANEL_INSET * 2,
            )

            val shopBalanceWidth = SHOP_BALANCE_WIDTH.coerceAtMost((shop.width / 2).coerceAtLeast(1))
            val shopBalanceBadge = MccRect(
                shop.right - PANEL_INSET - shopBalanceWidth,
                shop.top + 2,
                shopBalanceWidth,
                SHOP_BALANCE_HEIGHT,
            )
            val footerHeight = SHOP_FOOTER_HEIGHT.coerceAtMost((shop.height / 2).coerceAtLeast(1))
            val shopTrack = MccRect(
                shop.right - PANEL_INSET - SCROLL_TRACK_WIDTH,
                shop.top + TITLE_HEIGHT,
                SCROLL_TRACK_WIDTH,
                shop.height - TITLE_HEIGHT - footerHeight - PANEL_GAP - PANEL_INSET,
            )
            val shopViewport = MccRect(
                shop.left + PANEL_INSET,
                shopTrack.top,
                shopTrack.left - PANEL_GAP - shop.left - PANEL_INSET,
                shopTrack.height,
            )
            val footerTop = shop.bottom - footerHeight
            val controlsWidth = shop.width - PANEL_INSET * 2
            val quantityTop = footerTop + 14
            val decrement = MccRect(shop.left + PANEL_INSET, quantityTop, 22, SMALL_CONTROL_HEIGHT)
            val increment = MccRect(decrement.right + PANEL_GAP, quantityTop, 22, SMALL_CONTROL_HEIGHT)
            val bottomTop = shop.bottom - SMALL_CONTROL_HEIGHT - PANEL_INSET
            val purchase = MccRect(
                shop.left + PANEL_INSET,
                bottomTop,
                controlsWidth,
                SMALL_CONTROL_HEIGHT,
            )

            return ShopScreenLayout(
                shell,
                character,
                leaderboard,
                shop,
                characterViewport,
                leaderboardViewport,
                leaderboardTrack,
                leaderboardContentButtons,
                leaderboardFormatButtons,
                leaderboardLevelButtons,
                shopBalanceBadge,
                shopViewport,
                shopTrack,
                decrement,
                increment,
                purchase,
            )
        }

        private fun partition(bounds: MccRect, count: Int): List<MccRect> {
            val available = bounds.width - PANEL_GAP * (count - 1)
            return List(count) { index ->
                val start = available * index / count
                val end = available * (index + 1) / count
                MccRect(bounds.left + start + PANEL_GAP * index, bounds.top, end - start, bounds.height)
            }
        }
    }
}

class MccVerticalScrollMetrics private constructor(
    val maxOffset: Int,
    val thumbHeight: Int,
    private val trackHeight: Int,
) {
    private val maxThumbTravel: Int
        get() = (trackHeight - thumbHeight).coerceAtLeast(0)

    fun afterWheel(currentOffset: Int, wheelDelta: Double): Int =
        (currentOffset - wheelDelta * WHEEL_STEP).roundToInt().coerceIn(0, maxOffset)

    fun thumbTop(trackTop: Int, offset: Int): Int = trackTop + if (maxOffset == 0) {
        0
    } else {
        (offset.coerceIn(0, maxOffset).toLong() * maxThumbTravel / maxOffset).toInt()
    }

    fun offsetForThumbTop(trackTop: Int, thumbTop: Int): Int = if (maxThumbTravel == 0) {
        0
    } else {
        ((thumbTop - trackTop).coerceIn(0, maxThumbTravel).toLong() * maxOffset / maxThumbTravel).toInt()
    }

    companion object {
        fun calculate(viewportHeight: Int, contentHeight: Int, trackHeight: Int): MccVerticalScrollMetrics {
            require(viewportHeight > 0 && contentHeight > 0 && trackHeight > 0)
            val maxOffset = (contentHeight - viewportHeight).coerceAtLeast(0)
            val thumbHeight = if (maxOffset == 0) {
                trackHeight
            } else {
                (trackHeight.toLong() * viewportHeight / contentHeight).toInt().coerceIn(MIN_THUMB_HEIGHT, trackHeight)
            }
            return MccVerticalScrollMetrics(maxOffset, thumbHeight, trackHeight)
        }
    }
}

private const val LEADERBOARD_WIDTH_PERCENT = 36
private const val CHARACTER_TO_LEADERBOARD_NUMERATOR = 2
private const val CHARACTER_TO_LEADERBOARD_DENOMINATOR = 3
private const val MIN_CHARACTER_WIDTH = 60
private const val MIN_LEADERBOARD_WIDTH = 96
private const val MIN_SHOP_WIDTH = 96
private const val PANEL_GAP = 4
private const val PANEL_INSET = 4
private const val TITLE_HEIGHT = 16
private const val SHOP_BALANCE_WIDTH = 68
private const val SHOP_BALANCE_HEIGHT = 12
private const val CHARACTER_NAME_HEIGHT = 12
private const val SMALL_CONTROL_HEIGHT = 16
private const val SHOP_FOOTER_HEIGHT = 54
private const val SCROLL_TRACK_WIDTH = 8
private const val SHOP_CARD_HEIGHT = 28
private const val ITEM_GAP = 3
private const val LEADERBOARD_ROW_HEIGHT = 14
private const val WHEEL_STEP = 28
private const val MIN_THUMB_HEIGHT = 18
