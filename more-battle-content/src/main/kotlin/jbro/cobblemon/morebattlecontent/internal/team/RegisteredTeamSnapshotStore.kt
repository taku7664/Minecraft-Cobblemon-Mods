package jbro.cobblemon.morebattlecontent.internal.team

import java.util.Collections
import java.util.UUID

internal sealed interface TeamSnapshotCaptureResult {
    data object Stored : TeamSnapshotCaptureResult
    data object SourceUnavailable : TeamSnapshotCaptureResult
    data class RegistrationMismatch(val memberId: UUID) : TeamSnapshotCaptureResult
    data class SnapshotFailed(val memberId: UUID, val cause: RuntimeException) : TeamSnapshotCaptureResult
}

internal sealed interface TeamSnapshotMaterializationResult<out B> {
    class Created<B>(members: Collection<B>) : TeamSnapshotMaterializationResult<B> {
        val members: List<B> = Collections.unmodifiableList(ArrayList(members))
    }

    data object NoSnapshot : TeamSnapshotMaterializationResult<Nothing>
    data class SnapshotMismatch(val memberId: UUID) : TeamSnapshotMaterializationResult<Nothing>
    data class CopyFailed(val memberId: UUID, val cause: RuntimeException) : TeamSnapshotMaterializationResult<Nothing>
}

internal class RegisteredTeamSnapshotStore<S, R, T, B>(
    private val sourcesFor: (UUID) -> Collection<S>?,
    private val registrationOf: (S) -> R,
    private val memberIdOf: (R) -> UUID,
    private val battleLevelOf: (R) -> Int,
    private val snapshotOf: (S, battleLevel: Int) -> T,
    private val battleCopyOf: (T) -> B,
) {
    private val snapshots = HashMap<UUID, Snapshot<R, T>>()

    @Synchronized
    fun snapshot(playerId: UUID, members: List<R>): TeamSnapshotCaptureResult {
        val sources = sourcesFor(playerId) ?: return TeamSnapshotCaptureResult.SourceUnavailable
        val sourcesById = LinkedHashMap<UUID, S>()
        for (source in sources) {
            val memberId = memberIdOf(registrationOf(source))
            if (sourcesById.put(memberId, source) != null) {
                return TeamSnapshotCaptureResult.RegistrationMismatch(memberId)
            }
        }
        val registrations = LinkedHashMap<UUID, R>()
        val storedMembers = LinkedHashMap<UUID, T>()
        for (registration in members) {
            val memberId = memberIdOf(registration)
            val source = sourcesById[memberId]
                ?: return TeamSnapshotCaptureResult.RegistrationMismatch(memberId)
            if (registrationOf(source) != registration) {
                return TeamSnapshotCaptureResult.RegistrationMismatch(memberId)
            }
            val snapshot = try {
                snapshotOf(source, battleLevelOf(registration))
            } catch (exception: RuntimeException) {
                return TeamSnapshotCaptureResult.SnapshotFailed(memberId, exception)
            }
            registrations[memberId] = registration
            storedMembers[memberId] = snapshot
        }
        snapshots[playerId] = Snapshot(
            Collections.unmodifiableMap(registrations),
            Collections.unmodifiableMap(storedMembers),
        )
        return TeamSnapshotCaptureResult.Stored
    }

    @Synchronized
    fun materialize(playerId: UUID, members: List<R>): TeamSnapshotMaterializationResult<B> {
        val snapshot = snapshots[playerId] ?: return TeamSnapshotMaterializationResult.NoSnapshot
        val copies = ArrayList<B>(members.size)
        for (registration in members) {
            val memberId = memberIdOf(registration)
            if (snapshot.registrationsById[memberId] != registration) {
                return TeamSnapshotMaterializationResult.SnapshotMismatch(memberId)
            }
            val stored = snapshot.membersById[memberId]
                ?: return TeamSnapshotMaterializationResult.SnapshotMismatch(memberId)
            try {
                copies += battleCopyOf(stored)
            } catch (exception: RuntimeException) {
                return TeamSnapshotMaterializationResult.CopyFailed(memberId, exception)
            }
        }
        return TeamSnapshotMaterializationResult.Created(copies)
    }

    @Synchronized
    fun discard(playerId: UUID) {
        snapshots.remove(playerId)
    }

    private data class Snapshot<R, T>(
        val registrationsById: Map<UUID, R>,
        val membersById: Map<UUID, T>,
    )
}
