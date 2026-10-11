package kr.parkjh.pokefusion

/**
 * 9x6 합성 화면의 칸 배치. 베이스 → 재료(3x3) → 결과 순서로 읽히게 놓고,
 * 각 칸 위에 이름표를, 나머지 칸에는 테두리 유리판을 둔다.
 *
 * ```
 *  .  .  .  .  M  .  .  .  .     M = 재료 이름표
 *  .  b  .  m  m  m  .  p  .     b = 베이스 이름표, p = 결과 이름표
 *  .  B  .  m  m  m  >  P  .     B = 베이스, m = 재료, > = 화살표, P = 결과 미리보기
 *  .  .  .  m  m  m  .  .  .
 *  .  .  .  .  .  .  .  .  .
 *  .  .  .  .  C  .  .  .  .     C = 합성 확정
 * ```
 */
object PokeFusionLayout {
    const val ROWS = 6
    const val SIZE = ROWS * 9

    const val BASE_SLOT = 19
    const val PREVIEW_SLOT = 25
    const val CONFIRM_SLOT = 49

    const val BASE_LABEL_SLOT = 10
    const val MATERIAL_LABEL_SLOT = 4
    const val PREVIEW_LABEL_SLOT = 16
    const val ARROW_SLOT = 24

    /** 입력 순서대로 왼쪽 위부터 채운다. */
    val MATERIAL_SLOTS: List<Int> = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    fun materialIndexOf(slotIndex: Int): Int = MATERIAL_SLOTS.indexOf(slotIndex)

    /** 아무 기능이 없는 칸. 클릭해도 반응하지 않는 테두리로 채운다. */
    fun decorationSlots(): List<Int> {
        val used = MATERIAL_SLOTS + listOf(
            BASE_SLOT, PREVIEW_SLOT, CONFIRM_SLOT,
            BASE_LABEL_SLOT, MATERIAL_LABEL_SLOT, PREVIEW_LABEL_SLOT, ARROW_SLOT
        )
        return (0 until SIZE).filterNot { it in used }
    }
}
