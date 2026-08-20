package jbro.cobblemon.morebattlecontent.internal.pvp

/**
 * The battle lounge dimension only ever holds generated arenas, so nothing in it is a player build.
 * Every world edit there is refused, and operators keep a bypass so a damaged arena stays fixable.
 */
internal object PvpArenaProtectionPolicy {
    /** Matches the level the mod's other administrative entry points require. */
    const val BYPASS_PERMISSION_LEVEL = 2

    fun protects(inLoungeDimension: Boolean, hasBypassPermission: Boolean): Boolean =
        inLoungeDimension && !hasBypassPermission
}
