package jbro.cobblemon.battleui.extended.ui.shared

/**
 * Battle UI's local implementation of the repository-wide semantic UI roles.
 *
 * This object intentionally contains values only. It does not depend on MBC or
 * expose a shared widget lifecycle across otherwise independent mods.
 */
internal object BattleUiDesignTokens {
    val SCRIM = 0x6A030612u.toInt()
    val SHELL = 0xFF080E1Du.toInt()
    val HEADER = 0xFF0C1528u.toInt()
    val PANEL = 0xFF101A2Du.toInt()
    val PANEL_ALT = 0xFF0C1525u.toInt()
    val BORDER = 0xFF274562u.toInt()
    val BORDER_BRIGHT = 0xFF3F7896u.toInt()
    val TRACK = 0xFF101724u.toInt()

    val ACCENT_PRIMARY = 0xFF39E4E4u.toInt()
    val ACCENT_SECONDARY = 0xFF9868FFu.toInt()
    val ACCENT_CAUTION = 0xFFFFC84Au.toInt()
    val ACCENT_DANGER = 0xFFFF667Au.toInt()
    val ACCENT_GOOD = 0xFF62E39Bu.toInt()

    val TEXT_PRIMARY = 0xFFEAF7FFu.toInt()
    val TEXT_SECONDARY = 0xFFB9CAD8u.toInt()
    val TEXT_DIM = 0xFF71859Au.toInt()
}
