package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorld

internal data class NativeChanceSampleAllocation(
    val world: NativeInitialProductWorld,
    val sampleCount: Int,
) {
    init {
        require(sampleCount > 0)
    }
}

/**
 * Shares one deterministic chance-particle budget across the complete hidden-world posterior.
 *
 * Every retained hidden world receives one particle first, so a low-probability legal build is not
 * deleted merely because random sampling is narrower than inference. Remaining particles use the
 * largest-remainder method over posterior mass with deterministic ID tie-breaking.
 */
internal object NativeChanceSampleAllocator {
    fun allocate(
        worlds: List<NativeInitialProductWorld>,
        sampleBudget: Int,
    ): List<NativeChanceSampleAllocation> {
        require(worlds.isNotEmpty())
        require(sampleBudget > 0)
        if (worlds.size >= sampleBudget) {
            return worlds.map { NativeChanceSampleAllocation(it, 1) }
        }

        val remaining = sampleBudget - worlds.size
        val extras = IntArray(worlds.size)
        val remainders = worlds.mapIndexed { index, world ->
            val exact = remaining * world.probability
            val whole = exact.toInt()
            extras[index] = whole
            SampleRemainder(index, exact - whole, world.probability, world.hypothesisId)
        }
        var unassigned = remaining - extras.sum()
        remainders.sortedWith(
            compareByDescending<SampleRemainder> { it.fraction }
                .thenByDescending { it.probability }
                .thenBy { it.hypothesisId },
        ).forEach { remainder ->
            if (unassigned <= 0) return@forEach
            extras[remainder.index]++
            unassigned--
        }
        check(unassigned == 0)
        return worlds.mapIndexed { index, world ->
            NativeChanceSampleAllocation(world, 1 + extras[index])
        }
    }

    private data class SampleRemainder(
        val index: Int,
        val fraction: Double,
        val probability: Double,
        val hypothesisId: String,
    )
}
