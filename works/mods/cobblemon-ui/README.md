# Cobblemon UI

**A clearer, customizable battle interface for Cobblemon.**

Cobblemon UI replaces battle menus, health displays, information screens, and battle narration with a coordinated interface. Choose a battle theme, navigate with the keyboard or mouse, and review the battle history without leaving the battle screen.

## Features

- Redesigned battle commands, move selection, switching, target selection, and forfeit confirmation.
- A battle information screen with team previews, weather, terrain, side effects, stat changes, and revealed Pokémon information.
- Move tooltips with power, accuracy, PP, type effectiveness, and critical-hit information.
- Battle dialogue and a scrollable, turn-organized history with Pokémon portraits.
- Champions and Galar battle themes, optional battle entry effects, and shared window styles and color palettes.
- English and Korean interface translations.

This is a client mod. Install it on your own client for singleplayer or multiplayer; the dedicated server does not need it. Vanilla inventory, crafting, and general Minecraft menus are outside its scope. It also supplies UI components to other mods; those mods' screens are only available when those mods are installed.

## Installation

| Requirement | Version |
| --- | --- |
| Minecraft Java Edition | 1.21.1 |
| Mod loader | Fabric Loader 0.19.5 or newer |
| Cobblemon | 1.8.1 |
| Java | 21 or newer |
| Fabric API | Required |
| Fabric Language Kotlin | 1.12.0 or newer |
| Mod Menu + Cloth Config | Optional; install both for the in-game settings screen |

Place the mod JAR and required dependencies in your client's `mods` folder. Other Cobblemon versions and NeoForge are not declared compatible. If a modpack already bundles Cobblemon UI, check its installed mod list before adding a standalone copy. Avoid combining multiple mods that replace the same battle screens; compatibility with other replacements has not been verified.

## Controls

| Default key | Action |
| --- | --- |
| Arrow keys / W, A, S, D | Navigate battle choices |
| Z | Confirm a choice or advance dialogue |
| X | Cancel or go back |
| Tab | Open or close battle information |
| Left Shift | Open or close battle history |

History supports the mouse wheel, Up/Down, Page Up/Down, and Home/End. X or Escape closes it. Opening history does not advance dialogue or submit a battle action. Rebind the mod's registered keys in Minecraft's Controls menu.

## Settings

Open **Mods → Cobblemon UI → Configure** with Mod Menu and Cloth Config installed. Settings are saved to `config/cobblemon_ui.json` in the active Minecraft profile. Without Cloth Config, the mod still runs and its settings button explains the missing dependency. Close the game before editing the file manually. When the current file is absent, the older `cobblemon_battle_ui.json` file is read as a migration source.

| Category | Options and defaults |
| --- | --- |
| Theme | Champions battle theme; battle entry transition on; rounded shared windows; Tower lobby colors |
| Features | Team preview, battle information, move tooltips, and damage/healing percentages on |
| UI Size | History, Pokémon tooltip, and move tooltip text scales: 1.0; accepted range 0.5–2.0 |
| Tooltip Options | Known Tera type off; own stat values on; estimated opponent Speed range on; base critical-hit rate off |

Galar is the second battle theme. Shared windows also offer pixel frames and six Tower/Factory palettes. Shared style and palette affect dialogue and screens provided by mods using this UI library; they do not add those mods' features.

Opponent Speed ranges are estimates. Unrevealed battle information and uncertain effect durations should not be treated as exact values. Legacy log visibility, size/position, and team-indicator placement fields remain in the saved format for compatibility; the current history and information screens use their own layouts. The old `enableBattleLog` flag does not turn off the current narration/history.

## License and credits

Project-authored code and accompanying project files are distributed under the [MIT License](LICENSE), consistent with this repository's existing license. The current battle interface has been independently redesigned. Some internal battle-state tracking code remains from the early work based on [Cobblemon Extended Battle UI by sveniik](https://github.com/sveniik/CobblemonExtendedBattleUI); its original [MIT notice](THIRD_PARTY_LICENSE_CobblemonExtendedBattleUI) is retained and packaged with the JAR. See [NOTICE.md](NOTICE.md) for scope and asset-review limits.

Cobblemon UI is an independent add-on. It is not an official Pokémon, Mojang, Microsoft, Nintendo, Game Freak, or The Pokémon Company product.

## Development

See [the UI library and development gallery guide](docs/UI_KIT.md). From `works/`, run `./gradlew :cobblemon-ui:build`; the module's `check` task runs its `unitTest` task. Release outputs are written to `mods/cobblemon-ui/build/libs/`.
