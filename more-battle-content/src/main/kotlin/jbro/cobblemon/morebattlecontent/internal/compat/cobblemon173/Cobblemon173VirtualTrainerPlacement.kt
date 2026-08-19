package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import kotlin.math.abs
import kotlin.math.floor
import net.minecraft.world.phys.Vec3

internal object Cobblemon173VirtualTrainerPlacement {
    private const val SEARCH_RADIUS = 4
    private const val MAX_HEIGHT_DELTA = 4.0

    fun select(
        ideal: Vec3,
        referenceY: Double,
        surfaceAt: (x: Int, z: Int) -> Double?,
        hasClearance: (Vec3) -> Boolean,
        hasLineOfSight: (Vec3) -> Boolean,
    ): Vec3? {
        val originX = floor(ideal.x).toInt()
        val originZ = floor(ideal.z).toInt()
        return buildList {
            for (offsetX in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (offsetZ in -SEARCH_RADIUS..SEARCH_RADIUS) {
                    val blockX = originX + offsetX
                    val blockZ = originZ + offsetZ
                    val surfaceY = surfaceAt(blockX, blockZ) ?: continue
                    if (abs(surfaceY - referenceY) > MAX_HEIGHT_DELTA) continue
                    val candidate = Vec3(blockX + 0.5, surfaceY, blockZ + 0.5)
                    if (!hasClearance(candidate)) continue
                    add(
                        Candidate(
                            position = candidate,
                            visible = hasLineOfSight(candidate),
                            distanceSquared = candidate.distanceToSqr(ideal),
                            heightDelta = abs(surfaceY - referenceY),
                        ),
                    )
                }
            }
        }.minWithOrNull(
            compareByDescending<Candidate> { it.visible }
                .thenBy { it.distanceSquared }
                .thenBy { it.heightDelta }
                .thenBy { it.position.x }
                .thenBy { it.position.z },
        )?.position
    }

    private data class Candidate(
        val position: Vec3,
        val visible: Boolean,
        val distanceSquared: Double,
        val heightDelta: Double,
    )
}
