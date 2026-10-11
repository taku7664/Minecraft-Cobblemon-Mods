package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PokeFusionLayoutTest {
    @Test
    fun `materials form a 3x3 grid between base and preview`() {
        assertEquals(listOf(12, 13, 14, 21, 22, 23, 30, 31, 32), PokeFusionLayout.MATERIAL_SLOTS)
        assertEquals(FusionMaterialLogic.MAX_MATERIALS, PokeFusionLayout.MATERIAL_SLOTS.size)
        assertEquals(0, PokeFusionLayout.materialIndexOf(12))
        assertEquals(8, PokeFusionLayout.materialIndexOf(32))
        assertEquals(-1, PokeFusionLayout.materialIndexOf(PokeFusionLayout.BASE_SLOT))
    }

    @Test
    fun `every slot has exactly one role`() {
        val roles = PokeFusionLayout.MATERIAL_SLOTS + listOf(
            PokeFusionLayout.BASE_SLOT,
            PokeFusionLayout.PREVIEW_SLOT,
            PokeFusionLayout.CONFIRM_SLOT,
            PokeFusionLayout.BASE_LABEL_SLOT,
            PokeFusionLayout.MATERIAL_LABEL_SLOT,
            PokeFusionLayout.PREVIEW_LABEL_SLOT,
            PokeFusionLayout.ARROW_SLOT
        ) + PokeFusionLayout.decorationSlots()

        assertEquals((0 until PokeFusionLayout.SIZE).toList(), roles.sorted())
    }
}
