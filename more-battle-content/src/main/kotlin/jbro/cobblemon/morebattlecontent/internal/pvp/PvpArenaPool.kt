package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

internal data class PvpArenaLease(
    val index: Int,
    val centerX: Int,
    val centerY: Int,
    val centerZ: Int,
)

internal class PvpArenaPool(
    initialArenaCount: Int = 0,
    private val spacingBlocks: Int = 2_048,
    private val baseY: Int = 96,
) {
    private val leasesByMatch = LinkedHashMap<UUID, PvpArenaLease>()
    private val matchByArena = HashMap<Int, UUID>()

    var arenaCount: Int = initialArenaCount
        private set

    init {
        require(initialArenaCount >= 0) { "Initial arena count cannot be negative" }
        require(spacingBlocks >= 512) { "Arena spacing must keep simultaneous battles outside tracking range" }
    }

    @Synchronized
    fun acquire(matchId: UUID): PvpArenaLease {
        leasesByMatch[matchId]?.let { return it }
        val index = (0 until arenaCount).firstOrNull { it !in matchByArena } ?: arenaCount++
        val lease = PvpArenaLease(index, index * spacingBlocks, baseY, 0)
        leasesByMatch[matchId] = lease
        matchByArena[index] = matchId
        return lease
    }

    @Synchronized
    fun release(matchId: UUID): PvpArenaLease? = leasesByMatch.remove(matchId)?.also { matchByArena.remove(it.index) }

    @Synchronized
    fun leaseFor(matchId: UUID): PvpArenaLease? = leasesByMatch[matchId]

    @Synchronized
    fun activeLeases(): Map<UUID, PvpArenaLease> = leasesByMatch.toMap()
}
