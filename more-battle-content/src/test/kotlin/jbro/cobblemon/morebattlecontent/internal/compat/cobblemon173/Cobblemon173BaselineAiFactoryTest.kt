package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.ai.StrongBattleAI
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Cobblemon173BaselineAiFactoryTest {
    @Test
    fun `baseline uses Cobblemon trainer Strong AI for every supported skill`() {
        (0..5).forEach { skill ->
            assertInstanceOf(StrongBattleAI::class.java, Cobblemon173BaselineAiFactory.create(skill))
        }
    }

    @Test
    fun `baseline rejects skill values Cobblemon would silently clamp`() {
        assertThrows(IllegalArgumentException::class.java) { Cobblemon173BaselineAiFactory.create(-1) }
        assertThrows(IllegalArgumentException::class.java) { Cobblemon173BaselineAiFactory.create(6) }
    }
}
