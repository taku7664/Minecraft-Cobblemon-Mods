package kr.parkjh.pokefusion

object FusionMaterialLogic {
    const val MAX_MATERIALS = 9

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
