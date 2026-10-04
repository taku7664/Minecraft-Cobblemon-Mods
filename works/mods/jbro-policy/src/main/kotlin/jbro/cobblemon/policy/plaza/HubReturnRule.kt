package jbro.cobblemon.policy.plaza

/**
 * Which return point `/plaza exit` uses when players hop between the plaza and other hubs such as player rooms.
 *
 * The point is where the player stood before this trip through the hubs began: entering from outside saves it,
 * entering from another hub keeps the one already saved, and being anywhere outside every hub clears it. The room
 * mod keeps its own point by the same rule, so neither hub sends a player back and forth to the other.
 */
object HubReturnRule {
    const val PLAZA = "jbro_policy:plaza"

    fun shouldSaveOnEntry(source: String, hasPoint: Boolean, otherHubs: Collection<String>): Boolean =
        source != PLAZA && (!hasPoint || source !in otherHubs)

    /** A player seen in [current] has left every hub, so the saved point belongs to a finished trip. */
    fun isStale(current: String, otherHubs: Collection<String>): Boolean = current != PLAZA && current !in otherHubs
}
