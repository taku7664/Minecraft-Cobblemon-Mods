package jbro.cobblemon.mcc.client

internal data class MccRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int
        get() = left + width
    val bottom: Int
        get() = top + height

    fun inset(amount: Int): MccRect {
        require(amount >= 0)
        return MccRect(
            left = left + amount,
            top = top + amount,
            width = (width - amount * 2).coerceAtLeast(1),
            height = (height - amount * 2).coerceAtLeast(1),
        )
    }
}

internal data class MccPartyCardContentLayout(
    val index: Int,
    val portrait: MccRect,
    val textLeft: Int,
    val textRight: Int,
    val nameTop: Int,
    val detailsTop: Int,
)
