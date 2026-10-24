package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokeFusionLayoutTest {
    @Test
    fun `menu has one base, nine material and one preview slot`() {
        assertEquals(0, PokeFusionLayout.BASE_SLOT)
        assertEquals(10, PokeFusionLayout.PREVIEW_SLOT)
        assertEquals(11, PokeFusionLayout.SIZE)
        assertEquals(-1, PokeFusionLayout.materialIndexOf(PokeFusionLayout.BASE_SLOT))
        assertEquals(0, PokeFusionLayout.materialIndexOf(1))
        assertEquals(8, PokeFusionLayout.materialIndexOf(9))
        assertEquals(-1, PokeFusionLayout.materialIndexOf(PokeFusionLayout.PREVIEW_SLOT))
    }

    @Test
    fun `slots sit apart inside the fusion area`() {
        val materialRight = PokeFusionLayout.materialX(8) + 16
        assertTrue(PokeFusionLayout.BASE_X + 16 < PokeFusionLayout.MATERIAL_X)
        assertTrue(materialRight <= PokeFusionLayout.ARROW_X)
        // 결과 칸은 26x26 테두리로 그리고 오른쪽 7px 테두리 안에 들어가야 한다.
        assertTrue(PokeFusionLayout.ARROW_X + 24 <= PokeFusionLayout.PREVIEW_X - 5)
        assertTrue(PokeFusionLayout.PREVIEW_X + 21 <= PokeFusionLayout.IMAGE_WIDTH - 7)
        assertTrue(PokeFusionLayout.materialY(8) + 16 < PokeFusionLayout.FUSION_AREA_BOTTOM)
    }
}
