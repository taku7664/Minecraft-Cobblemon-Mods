package jbro.cobblemon.morebattlecontent.internal.pvp

/**
 * Shared dimensions of a generated PvP arena. The arena builder and the client floor effect both read
 * these, so resizing the arena moves the lighting with it instead of leaving a hard-coded copy behind.
 */
internal object PvpArenaGeometry {
    /** Radius of the circular floor, in blocks, measured from the arena centre. */
    const val FLOOR_RADIUS_BLOCKS = 22

    /** Height of the glass enclosure above the floor, in blocks. */
    const val WALL_HEIGHT_BLOCKS = 12
}
