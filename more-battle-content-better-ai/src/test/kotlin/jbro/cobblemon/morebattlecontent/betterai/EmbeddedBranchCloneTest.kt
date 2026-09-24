package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedBranchCloneTest {
    @Test
    fun `serialized native battle forks independently and deterministically`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.branchClone(directory)

        assertEquals("COMPLETE", result["status"].asString)
        assertEquals("Battle.toJSON/fromJSON", result["cloneApi"].asString)
        assertEquals(result["sourceBefore"], result["sourceAfter"])
        assertEquals(result["sourceStateBefore"], result["sourceStateAfter"])
        assertEquals(result["attackFirst"], result["attackReplay"])
        assertNotEquals(result["attackFirst"], result["setupBranch"])

        val source = result.getAsJsonObject("sourceAfter")
        val attacked = result.getAsJsonObject("attackFirst").getAsJsonObject("view")
        val setup = result.getAsJsonObject("setupBranch").getAsJsonObject("view")
        assertEquals(2, source["turn"].asInt)
        assertTrue(source["p2Hp"].asInt < source["p2MaxHp"].asInt,
            "the serialized source must be a progressed battle, not the initial request")
        assertTrue(attacked["p2Hp"].asInt < source["p2Hp"].asInt)
        assertEquals(source["p2Hp"].asInt, setup["p2Hp"].asInt)
        assertEquals(2, setup["p1AttackBoost"].asInt)
        assertEquals(0, source["p1AttackBoost"].asInt)

        val technicianDamage = result["technicianDamage"].asInt
        val controlDamage = result["controlDamage"].asInt
        assertTrue(technicianDamage > controlDamage,
            "the native branch must execute Technician rather than only replaying commands")
    }
}
