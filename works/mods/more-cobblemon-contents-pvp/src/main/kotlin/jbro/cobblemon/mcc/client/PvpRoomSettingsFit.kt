package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubChipRows
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiRect

/**
 * How the room view splits its body between the two seats and the settings card. The settings chips need more height
 * the narrower their card is (labels move above the chips, chips wrap), so on a small hub the seats give up width until
 * every setting fits.
 */
internal object PvpRoomSettingsFit {
    /** Column weights from roomiest seats to roomiest settings: left seat, right seat, settings. */
    val WEIGHTS: List<IntArray> = listOf(intArrayOf(3, 3, 4), intArrayOf(2, 2, 4), intArrayOf(1, 1, 3))

    /** One settings row as text widths: its title and each chip label. */
    class Row(val titleWidth: Int, val chipWidths: List<Int>)

    /** The seats and settings columns of [body]: the first split whose settings card body holds [rows], else the last. */
    fun columns(body: UiRect, rows: List<Row>): List<UiRect> {
        var columns = MccHubKit.columns(body, *WEIGHTS.first())
        for (weights in WEIGHTS) {
            columns = MccHubKit.columns(body, *weights)
            val settings = MccHubKit.cardBody(columns[2])
            if (height(settings.width, rows) <= settings.height) return columns
        }
        return columns
    }

    /** The height [MccHubChipRows.place] gives [rows] across [width], by the same rules. */
    fun height(width: Int, rows: List<Row>): Int {
        if (rows.isEmpty()) return 0
        val labelWidth = rows.maxOf { it.titleWidth } + 6
        val besideWidth = (width - labelWidth).coerceAtLeast(1)
        val stacked = rows.any { it.chipWidths.size > 1 && MccHubChipRows.perLine(besideWidth, it.chipWidths) == 1 }
        val chipsWidth = (if (stacked) width else besideWidth).coerceAtLeast(1)
        return rows.sumOf { row ->
            val lines = (row.chipWidths.size + MccHubChipRows.perLine(chipsWidth, row.chipWidths) - 1) /
                MccHubChipRows.perLine(chipsWidth, row.chipWidths)
            (if (stacked) LABEL_LINE else 0) + lines * MccHubChipRows.CHIP_HEIGHT + (lines - 1) * CHIP_GAP
        } + (rows.size - 1) * ROW_GAP
    }

    // MccHubChipRows' spacing, which it keeps private.
    private const val LABEL_LINE = 11
    private const val CHIP_GAP = 2
    private const val ROW_GAP = 4
}
