package kr.parkjh.pokefusion

/**
 * 합성 화면의 칸 구성과 화면 좌표. 서버 메뉴와 클라이언트 화면이 같은 값을 쓴다.
 *
 * ```
 *              재료 n/9
 *   베이스     [m][m][m]           결과
 *    [B]       [m][m][m]   ──▶   [P]
 *              [m][m][m]
 * ```
 */
object PokeFusionLayout {
    const val BASE_SLOT = 0
    const val FIRST_MATERIAL_SLOT = 1
    const val PREVIEW_SLOT = FIRST_MATERIAL_SLOT + FusionMaterialLogic.MAX_MATERIALS
    const val SIZE = PREVIEW_SLOT + 1

    fun materialIndexOf(slotIndex: Int): Int =
        if (slotIndex in FIRST_MATERIAL_SLOT until PREVIEW_SLOT) slotIndex - FIRST_MATERIAL_SLOT else -1

    /** 화면 좌표(GUI 왼쪽 위 기준, 칸 안쪽 아이템 위치). */
    const val IMAGE_WIDTH = 176
    const val FUSION_AREA_BOTTOM = 111
    const val IMAGE_HEIGHT = FUSION_AREA_BOTTOM + 96

    const val BASE_X = 26
    const val BASE_Y = 54
    const val MATERIAL_X = 62
    const val MATERIAL_Y = 36
    const val PREVIEW_X = 145
    const val PREVIEW_Y = 54
    const val ARROW_X = 116
    const val ARROW_Y = 54

    const val INVENTORY_X = 8
    const val INVENTORY_Y = FUSION_AREA_BOTTOM + 14
    const val HOTBAR_Y = FUSION_AREA_BOTTOM + 72

    fun materialX(index: Int): Int = MATERIAL_X + (index % 3) * 18
    fun materialY(index: Int): Int = MATERIAL_Y + (index / 3) * 18
}
