package jbro.cobblemon.ui.extended

/**
 * Whether the confirm key now held was pressed for the battle menu. A press made while the dialogue still spoke (to
 * advance it) and held on keeps repeating once the menu appears; the menu takes only a press made while it showed,
 * so holding the key through the last line never picks a move by itself.
 */
object BattleConfirmGate {
    @JvmStatic
    var pressedForMenu: Boolean = false
        private set

    private var menuPressAvailable: Boolean = false

    /** The confirm key went down; [menuShown] tells whether the menu was there to take it. */
    @JvmStatic
    fun pressed(menuShown: Boolean) {
        pressedForMenu = menuShown
        menuPressAvailable = menuShown
    }

    @JvmStatic
    fun released() {
        pressedForMenu = false
        menuPressAvailable = false
    }

    /** Consumes the physical press once, so a held key's repeat events cannot toggle a menu control repeatedly. */
    @JvmStatic
    fun consumeMenuPress(): Boolean {
        if (!pressedForMenu || !menuPressAvailable) return false
        menuPressAvailable = false
        return true
    }
}
