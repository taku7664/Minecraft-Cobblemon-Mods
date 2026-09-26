package jbro.cobblemon.morebattlecontent.api.access

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentAccessPoliciesTest {
    private val player = UUID.randomUUID()

    @Test fun `no addon allows existing content`() {
        assertEquals(ContentAccessDecision.Allowed, ContentAccessPolicies().check(player, "mbc:tower", ContentAccessAction.START))
    }

    @Test fun `denial wins and handle removes only its own policy`() {
        val policies = ContentAccessPolicies()
        val denied = ContentAccessDecision.Denied("league.locked", "champion_required")
        val handle = policies.register(setOf("mbc:tower")) { _, _, _ -> denied }
        policies.register(setOf("mbc:tower")) { _, _, _ -> ContentAccessDecision.Allowed }
        assertEquals(denied, policies.check(player, "mbc:tower", ContentAccessAction.START))
        assertEquals(ContentAccessDecision.Allowed, policies.check(player, "mbc:pvp", ContentAccessAction.START))
        handle.close()
        handle.close()
        assertEquals(ContentAccessDecision.Allowed, policies.check(player, "mbc:tower", ContentAccessAction.START))
    }

    @Test fun `broken provider fails closed only within its declared scope`() {
        val policies = ContentAccessPolicies()
        policies.register(setOf("mbc:tower")) { _, _, _ -> error("provider unavailable") }
        assertInstanceOf(ContentAccessDecision.Denied::class.java, policies.check(player, "mbc:tower", ContentAccessAction.OPEN))
        assertEquals(ContentAccessDecision.Allowed, policies.check(player, "mbc:factory", ContentAccessAction.OPEN))
    }
}
