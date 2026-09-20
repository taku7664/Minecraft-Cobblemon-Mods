package kr.parkjh.pokefusion

object FusionMaterialLogic {
    const val MAX_MATERIALS = 9
    private const val MATERIAL_ROW_START = 27
    private const val MATERIAL_SECOND_ROW_START = 36
    private const val MATERIALS_PER_ROW = 5
    private const val MENU_COLUMNS = 9

    enum class InputDestination {
        BASE,
        MATERIAL,
        NONE
    }

    fun nextDestination(baseEmpty: Boolean, materialCount: Int): InputDestination {
        require(materialCount in 0..MAX_MATERIALS) { "material count must be between 0 and $MAX_MATERIALS" }
        return when {
            baseEmpty -> InputDestination.BASE
            materialCount < MAX_MATERIALS -> InputDestination.MATERIAL
            else -> InputDestination.NONE
        }
    }

    fun visibleSlots(materialCount: Int): List<Int> {
        require(materialCount in 0..MAX_MATERIALS) { "material count must be between 0 and $MAX_MATERIALS" }
        val visibleCount = minOf(MAX_MATERIALS, materialCount + 1)
        val firstRowCount = minOf(MATERIALS_PER_ROW, visibleCount)
        val secondRowCount = visibleCount - firstRowCount
        return centeredRowSlots(MATERIAL_ROW_START, firstRowCount) +
            centeredRowSlots(MATERIAL_SECOND_ROW_START, secondRowCount)
    }

    private fun centeredRowSlots(rowStart: Int, count: Int): List<Int> {
        if (count == 0) return emptyList()
        val firstColumn = (MENU_COLUMNS - count) / 2
        return (firstColumn until firstColumn + count).map(rowStart::plus)
    }

    fun contributions(base: IntArray, materials: List<IntArray>): List<Boolean> {
        require(materials.all { it.size == base.size }) { "every material must have the same stat count as the base" }
        if (materials.isEmpty()) return emptyList()

        val finalValues = base.copyOf()
        for (material in materials) {
            for (index in finalValues.indices) {
                finalValues[index] = maxOf(finalValues[index], material[index])
            }
        }

        return materials.map { material ->
            material.indices.any { index ->
                material[index] > base[index] && material[index] == finalValues[index]
            }
        }
    }
}
