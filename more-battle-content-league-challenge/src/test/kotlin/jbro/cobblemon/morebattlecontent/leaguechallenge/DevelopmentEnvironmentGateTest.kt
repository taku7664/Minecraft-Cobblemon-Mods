package jbro.cobblemon.morebattlecontent.leaguechallenge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevelopmentEnvironmentGateTest {
    @Test
    fun `development entry route is registered only in development environments`() {
        assertTrue(DevelopmentEnvironmentGate.shouldRegister(isDevelopmentEnvironment = true))
        assertFalse(DevelopmentEnvironmentGate.shouldRegister(isDevelopmentEnvironment = false))
    }
}
