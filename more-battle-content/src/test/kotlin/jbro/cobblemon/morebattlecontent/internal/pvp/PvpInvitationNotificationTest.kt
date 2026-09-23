package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PvpInvitationNotificationTest {
    private val request = PvpChallengeRequest(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        PvpBattleFormat.SINGLE,
    )
    private val challenge = PvpChallenge(request, PvpChallengePhase.PENDING)

    @Test
    fun `new invitation rolls back when its notification fails`() {
        val failure = IllegalStateException("chat send failed")
        var rolledBack: PvpChallenge? = null

        val thrown = assertThrows(IllegalStateException::class.java) {
            applyPvpInvitationWithNotification(
                invite = { PvpChallengeMutationResult.Applied(challenge) },
                notify = { throw failure },
                rollback = { rolledBack = it },
            )
        }

        assertSame(failure, thrown)
        assertEquals(challenge, rolledBack)
    }

    @Test
    fun `idempotent invitation does not notify or roll back again`() {
        var notifications = 0
        var rollbacks = 0

        val result = applyPvpInvitationWithNotification(
            invite = { PvpChallengeMutationResult.Unchanged(challenge) },
            notify = { notifications++ },
            rollback = { rollbacks++ },
        )

        assertEquals(PvpChallengeMutationResult.Unchanged(challenge), result)
        assertEquals(0, notifications)
        assertEquals(0, rollbacks)
    }
}
