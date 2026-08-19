package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173VirtualTrainerActorTest {
    @Test
    fun `Brain trainer exposes the entity backed contract required by initial switch instructions`() {
        assertTrue(
            EntityBackedBattleActor::class.java.isAssignableFrom(Cobblemon173BrainTrainerBattleActor::class.java),
        )
    }

    @Test
    fun `virtual trainer anchor is placed eight blocks in front of the player`() {
        val origin = Vec3(4.0, 72.0, 6.0)

        val south = Cobblemon173VirtualTrainerAnchor.position(origin, yawDegrees = 0.0F)
        val west = Cobblemon173VirtualTrainerAnchor.position(origin, yawDegrees = 90.0F)

        assertVec3(Vec3(4.0, 72.0, 14.0), south)
        assertVec3(Vec3(-4.0, 72.0, 6.0), west)
    }

    @Test
    fun `safe placement skips blocked target and prefers a visible nearby column`() {
        val ideal = Vec3(8.0, 64.0, 0.0)

        val selected = Cobblemon173VirtualTrainerPlacement.select(
            ideal = ideal,
            referenceY = 64.0,
            surfaceAt = { x, z -> if ((x == 8 || x == 7) && z == 0) 64.0 else null },
            hasClearance = { candidate -> candidate.x < 8.0 },
            hasLineOfSight = { true },
        )

        assertVec3(Vec3(7.5, 64.0, 0.5), requireNotNull(selected))
    }

    @Test
    fun `safe placement prefers visible candidate and reports no result when every surface is invalid`() {
        val ideal = Vec3(8.0, 64.0, 0.0)
        val visible = Cobblemon173VirtualTrainerPlacement.select(
            ideal = ideal,
            referenceY = 64.0,
            surfaceAt = { x, z -> if ((x == 8 || x == 7) && z == 0) 64.0 else null },
            hasClearance = { true },
            hasLineOfSight = { candidate -> candidate.x < 8.0 },
        )

        assertVec3(Vec3(7.5, 64.0, 0.5), requireNotNull(visible))
        assertNull(
            Cobblemon173VirtualTrainerPlacement.select(
                ideal = ideal,
                referenceY = 64.0,
                surfaceAt = { _, _ -> 70.0 },
                hasClearance = { true },
                hasLineOfSight = { true },
            ),
        )
    }

    private fun assertVec3(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1.0E-9)
        assertEquals(expected.y, actual.y, 1.0E-9)
        assertEquals(expected.z, actual.z, 1.0E-9)
    }
}
