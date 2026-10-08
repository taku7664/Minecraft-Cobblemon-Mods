package jbro.cobblemon.mcc.api.rules

/** Stable battle API bits. Combine with `or`; zero disables every mechanic. */
object BattleMechanicFlags {
    const val NONE = 0
    const val MEGA = 1
    const val DYNAMAX = 2
    const val TERA = 4
    const val Z_MOVE = 8
    const val ALL = MEGA or DYNAMAX or TERA or Z_MOVE

    @JvmStatic
    fun requireValid(flags: Int) {
        require(flags and ALL.inv() == 0) { "Unknown battle mechanic flags: $flags" }
    }

    @JvmStatic
    fun contains(flags: Int, mechanic: Int): Boolean = mechanic != NONE && flags and mechanic == mechanic

    /** Adapts content that deliberately selects just one major mechanic. */
    @JvmStatic
    fun fromMajor(mechanic: MajorBattleMechanic?): Int = when (mechanic) {
        MajorBattleMechanic.MEGA -> MEGA
        MajorBattleMechanic.DYNAMAX -> DYNAMAX
        MajorBattleMechanic.TERA -> TERA
        null -> NONE
    }
}
