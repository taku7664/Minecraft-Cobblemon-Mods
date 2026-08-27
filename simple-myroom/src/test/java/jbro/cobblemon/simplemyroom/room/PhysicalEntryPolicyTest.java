package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhysicalEntryPolicyTest {
    @Test
    void ejectsOnlyUnauthorizedNonAdminEntrantsWhenEnabled() {
        assertTrue(PhysicalEntryPolicy.shouldEject(true, false, false));
        assertFalse(PhysicalEntryPolicy.shouldEject(true, true, false));
        assertFalse(PhysicalEntryPolicy.shouldEject(true, false, true));
        assertFalse(PhysicalEntryPolicy.shouldEject(false, false, false));
    }
}
