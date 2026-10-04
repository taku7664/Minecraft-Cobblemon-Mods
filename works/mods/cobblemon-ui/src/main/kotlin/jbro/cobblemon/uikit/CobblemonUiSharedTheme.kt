package jbro.cobblemon.uikit

/**
 * The look the Cobblemon screens share: the MCC hub and its tabs, the HUD buttons drawn over the world, and Cobblemon
 * UI's dialogue box. It is a UI kit [style] drawn in a [palette], DS menu windows in the Battle Tower lobby's colours
 * by default; selecting another restyles every one of them.
 *
 * Screens that draw through the UI kit's widgets [install] it while they are open. Screens that draw for themselves
 * read [snapshot] directly.
 */
object CobblemonUiSharedTheme {
    var style: UiThemeStyle = UiThemeStyle.DS_WINDOW
        private set
    var palette: UiPalettePreset = UiPalettePreset.TOWER_LOBBY
        private set

    /** The theme id, `style.palette`, as the registry reports it while the theme is installed. */
    val id: String get() = CobblemonUiThemeComposer.id(style, palette)

    fun snapshot(): UiThemeSnapshot = CobblemonUiThemeComposer.compose(style, palette)

    /**
     * Selects the theme by its id, `style.palette` (for example `ds_window.tower_lobby`); false if either part is
     * unknown. A screen that has it installed in [registry] switches to the new one at once.
     */
    fun select(id: String, registry: UiThemeRegistry = CobblemonUiThemes.registry): Boolean {
        val style = UiThemeStyle.entries.firstOrNull { id.startsWith(it.id + ".") } ?: return false
        val palette = UiPalettePreset.fromId(id.removePrefix(style.id + ".")) ?: return false
        val installed = registry.snapshot().id == this.id
        this.style = style
        this.palette = palette
        if (installed) registry.install(snapshot())
        return true
    }

    /**
     * Installs the theme in [registry] and returns what puts the previous theme back. Restoring leaves alone a theme
     * that someone else installed in the meantime.
     */
    fun install(registry: UiThemeRegistry = CobblemonUiThemes.registry): () -> Unit {
        val previous = registry.snapshot()
        registry.install(snapshot())
        return { if (registry.snapshot().id == id) registry.install(previous) }
    }
}
