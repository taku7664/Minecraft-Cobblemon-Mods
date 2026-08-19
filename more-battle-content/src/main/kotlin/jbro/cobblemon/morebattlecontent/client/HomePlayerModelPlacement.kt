package jbro.cobblemon.morebattlecontent.client

import kotlin.math.min

internal data class HomePlayerModelPlacement(
    val centerX: Int,
    val centerY: Int,
    val scale: Int,
) {
    companion object {
        fun calculate(viewport: TowerPlayRect, entityHeight: Float): HomePlayerModelPlacement {
            require(entityHeight > 0f) { "Player model height must be positive" }
            val heightScale = (viewport.height * HEIGHT_USAGE / entityHeight).toInt()
            val widthScale = (viewport.width * WIDTH_USAGE).toInt()
            return HomePlayerModelPlacement(
                centerX = viewport.left + viewport.width / 2,
                centerY = PlayerModelCentering.centerY(viewport.top, viewport.bottom),
                scale = min(heightScale, widthScale).coerceIn(1, MAX_SCALE),
            )
        }
    }
}

internal object PlayerModelCentering {
    fun centerY(top: Int, bottomExclusive: Int): Int {
        require(bottomExclusive > top) { "Player model viewport must have positive height" }
        return top + (bottomExclusive - top) / 2
    }
}

private const val HEIGHT_USAGE = 0.78f
private const val WIDTH_USAGE = 0.82f
private const val MAX_SCALE = 120
