package jbro.cobblemon.policy.pack

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PackCheckTest {
    @Test fun `only the first two numbers have to match`() {
        assertTrue(PackCheck.sameLine("1.2.0", "1.2.0"))
        assertTrue(PackCheck.sameLine("1.2.3", "1.2.0"))
        assertTrue(PackCheck.sameLine("1.2", "1.2.7"))
        assertFalse(PackCheck.sameLine("1.1.0", "1.2.0"))
        assertFalse(PackCheck.sameLine("1.3.0", "1.2.0"))
        assertFalse(PackCheck.sameLine("2.2.0", "1.2.0"))
    }

    @Test fun `an unreadable version never matches`() {
        assertFalse(PackCheck.sameLine("", "1.2.0"))
        assertFalse(PackCheck.sameLine("1", "1.2.0"))
        assertFalse(PackCheck.sameLine("one.two", "1.2.0"))
    }
}
