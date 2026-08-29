package kr.parkjh.pokefusion

object FusionMaterialLogic {
    const val MAX_MATERIALS = 9
    private const val MATERIAL_ROW_START = 27

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
        val columns = when (visibleCount) {
            1 -> listOf(4)
            2 -> listOf(3, 5)
            3 -> listOf(3, 4, 5)
            4 -> listOf(2, 3, 5, 6)
            5 -> (2..6).toList()
            6 -> listOf(1, 2, 3, 5, 6, 7)
            7 -> (1..7).toList()
            8 -> listOf(0, 1, 2, 3, 5, 6, 7, 8)
            else -> (0..8).toList()
        }
        return columns.map { MATERIAL_ROW_START + it }
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
