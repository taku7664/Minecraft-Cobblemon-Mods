# Cobblemon UI Kit

Client-side shared widget and theme source module for Cobblemon interfaces.

## Development gallery

Enter a development world and run:

```text
/cobblemon-ui-gallery
```

For a quick-play world that opens the gallery and leaves it under manual control:

```powershell
$env:COBBLEMON_UI_KIT_MANUAL_GALLERY='1'
.\gradlew.bat :cobblemon-ui-kit:runClient --no-daemon --configure-on-demand
```

Create or enter any world. The gallery opens after the player joins and remains under manual control.

Choose one of the built-in theme presets before starting the client:

```powershell
$env:COBBLEMON_UI_KIT_THEME='galar_stadium'
```

Supported IDs are `league_neon`, `pixel_league`, `galar_stadium`, `paldea_portal`, `hoenn_pixel`, `johto_touch`, and `unova_pixel`. `pixel_league` is the actual sprite-backed pixel UI candidate; the three generation-named presets remain palette studies.

`COBBLEMON_UI_KIT_CAPTURE_WORLD=1` is reserved for automated capture. It deliberately exercises focus, scrolling, close, and then stops the client; do not use it for manual review. Add `COBBLEMON_UI_KIT_CAPTURE_ALL_THEMES=1` to capture the top and scrolled state of all six presets in one joined world. For repeatable local capture, pass an existing development world through Minecraft's quick-play argument:

```powershell
$env:COBBLEMON_UI_KIT_CAPTURE_WORLD='1'
$env:COBBLEMON_UI_KIT_CAPTURE_ALL_THEMES='1'
$env:COBBLEMON_UI_KIT_ACCEPT_SNAPSHOT_WARNING='1'
.\gradlew.bat :cobblemon-ui-kit:runClient --no-daemon --configure-on-demand --args="--quickPlaySingleplayer ui-kit-clean"
```

`COBBLEMON_UI_KIT_ACCEPT_SNAPSHOT_WARNING=1` is an explicit development-only opt-in. When a Cobblemon snapshot build presents its startup warning, the harness chooses **Yes** for that run without selecting “don't show again”. Stable Cobblemon builds do not exercise this branch.

The command is not registered outside Fabric's development environment. The gallery demonstrates semantic button variants and sizes, content/fill widths, widget states, a scrollable list surface, progress styling, theme switching, and composable rectangle/chamfer, solid/gradient fill, optional border, and background-opacity styles in English and Korean.

Button text shadow is off by default. A theme may opt a style in with `UiButtonStyle.textShadow`, and a call site may explicitly override it with `UiButtonSpec(textShadow = true)` or `false`. Prefer the shadow-free default on opaque UI surfaces.

This module does not replace vanilla inventory, crafting, chat, or other general Minecraft screens. Its final distribution form remains undecided until both League Challenge and Cobblemon Battle UI consume the runtime contract.
