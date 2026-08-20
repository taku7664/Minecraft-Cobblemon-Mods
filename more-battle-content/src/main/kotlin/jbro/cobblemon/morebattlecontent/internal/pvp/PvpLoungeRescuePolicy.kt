package jbro.cobblemon.morebattlecontent.internal.pvp

/**
 * Decides whether somebody logging in inside the battle lounge has to be pulled out.
 *
 * The lounge only ever holds generated arenas, and the coordinator restores competitors and
 * spectators from a recorded return point. When no such record exists the player is stranded: a
 * server restart or a regenerated lounge leaves them standing in a dimension that is otherwise empty
 * air, with nothing to teleport them home.
 */
internal object PvpLoungeRescuePolicy {
    fun rescues(
        inLoungeDimension: Boolean,
        hasPendingReturn: Boolean,
    ): Boolean = inLoungeDimension && !hasPendingReturn
}
