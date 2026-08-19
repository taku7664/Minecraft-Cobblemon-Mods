package jbro.cobblemon.morebattlecontent.internal.spectate

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteSpectateServiceTest {
    private val viewerId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val targetId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val targetBattleId = UUID.fromString("30000000-0000-0000-0000-000000000003")
    private val otherBattleId = UUID.fromString("40000000-0000-0000-0000-000000000004")

    @Test
    fun `starts remote spectating for a managed MBC battle`() {
        val gateway = FakeGateway().apply {
            participantBattles[targetId] = targetBattleId
            managedBattles += targetBattleId
        }

        assertEquals(RemoteSpectateResult.STARTED, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(Triple(targetBattleId, targetId, viewerId), gateway.started)
    }

    @Test
    fun `rejects self spectating before consulting battle state`() {
        val gateway = FakeGateway()

        assertEquals(RemoteSpectateResult.SELF_SPECTATE, RemoteSpectateService(gateway).spectate(viewerId, viewerId))
        assertTrue(gateway.participantBattles.isEmpty())
        assertEquals(null, gateway.started)
    }

    @Test
    fun `honours the Cobblemon spectating setting`() {
        val gateway = FakeGateway().apply { spectatingEnabled = false }

        assertEquals(RemoteSpectateResult.SPECTATING_DISABLED, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(null, gateway.started)
    }

    @Test
    fun `rejects a target that is not battling`() {
        val gateway = FakeGateway()

        assertEquals(RemoteSpectateResult.TARGET_NOT_IN_BATTLE, RemoteSpectateService(gateway).spectate(viewerId, targetId))
    }

    @Test
    fun `rejects a battle not managed by MBC`() {
        val gateway = FakeGateway().apply { participantBattles[targetId] = targetBattleId }

        assertEquals(RemoteSpectateResult.TARGET_NOT_IN_MBC_BATTLE, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(null, gateway.started)
    }

    @Test
    fun `rejects a viewer who is participating in another battle`() {
        val gateway = managedGateway().apply { participantBattles[viewerId] = otherBattleId }

        assertEquals(RemoteSpectateResult.VIEWER_IN_BATTLE, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(null, gateway.started)
    }

    @Test
    fun `treats duplicate spectating of the same battle as idempotent success`() {
        val gateway = managedGateway().apply { spectatedBattles[viewerId] = targetBattleId }

        assertEquals(RemoteSpectateResult.ALREADY_SPECTATING, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(null, gateway.started)
    }

    @Test
    fun `rejects switching directly from another managed battle`() {
        val gateway = managedGateway().apply { spectatedBattles[viewerId] = otherBattleId }

        assertEquals(RemoteSpectateResult.VIEWER_SPECTATING_OTHER, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(null, gateway.started)
    }

    @Test
    fun `reports a battle that ended before spectator registration`() {
        val gateway = managedGateway().apply { startSucceeds = false }

        assertEquals(RemoteSpectateResult.BATTLE_UNAVAILABLE, RemoteSpectateService(gateway).spectate(viewerId, targetId))
        assertEquals(Triple(targetBattleId, targetId, viewerId), gateway.started)
    }

    private fun managedGateway() = FakeGateway().apply {
        participantBattles[targetId] = targetBattleId
        managedBattles += targetBattleId
    }

    private class FakeGateway : RemoteSpectateGateway {
        var spectatingEnabled = true
        val participantBattles = HashMap<UUID, UUID>()
        val managedBattles = HashSet<UUID>()
        val spectatedBattles = HashMap<UUID, UUID>()
        var startSucceeds = true
        var started: Triple<UUID, UUID, UUID>? = null

        override fun isSpectatingEnabled(): Boolean = spectatingEnabled

        override fun participatingBattleId(playerId: UUID): UUID? = participantBattles[playerId]

        override fun isManagedBattle(battleId: UUID): Boolean = battleId in managedBattles

        override fun spectatedManagedBattleId(playerId: UUID): UUID? = spectatedBattles[playerId]

        override fun beginSpectating(battleId: UUID, targetId: UUID, viewerId: UUID): Boolean {
            started = Triple(battleId, targetId, viewerId)
            return startSucceeds
        }
    }
}
