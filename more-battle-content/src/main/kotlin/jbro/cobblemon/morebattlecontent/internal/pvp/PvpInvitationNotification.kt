package jbro.cobblemon.morebattlecontent.internal.pvp

/** Applies a new invitation provisionally until its only user-visible notification succeeds. */
internal fun applyPvpInvitationWithNotification(
    invite: () -> PvpChallengeMutationResult,
    notify: (PvpChallenge) -> Unit,
    rollback: (PvpChallenge) -> Unit,
): PvpChallengeMutationResult {
    val result = invite()
    if (result is PvpChallengeMutationResult.Applied) {
        protectProvisionalPvpStateNotification(
            rollback = { rollback(result.challenge) },
            notify = { notify(result.challenge) },
        )
    }
    return result
}
