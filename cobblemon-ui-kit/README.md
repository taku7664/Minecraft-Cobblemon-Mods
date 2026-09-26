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

The command is not registered outside Fabric's development environment. The gallery demonstrates semantic button variants and sizes, square/circle/diamond icon-only buttons, rectangle/chamfer/rounded/capsule/circle/diamond surfaces, tabs, badges, toggles, list items, progress bars, checkbox and radio choices, combo boxes, cards, stat rows, wrapped text, panels, persistent callouts, step tracks, ordered selection, player/item/texture render slots, tooltips, modal dialogs, toast notifications, reusable layout and scroll primitives, theme switching, and composable fill, border, opacity, and shadow styles in English and Korean.

Reusable contracts live in `UiButtonContract.kt`, `UiSurfaceContract.kt`, `UiWidgetContract.kt`, `UiLayoutContract.kt`, `UiOverlayContract.kt`, `UiAdvancedWidgetContract.kt`, and `UiContentContract.kt`. Client widgets are exposed through the `CobblemonUi*` classes, including buttons, selection controls, data widgets, content primitives, render slots, layouts, overlays, and the scroll viewport.

`UiStackLayout`, `UiFlowLayout`, `UiGridLayout`, and `UiAnchorLayout` calculate deterministic logical rectangles without depending on Minecraft client classes. `CobblemonUiScrollViewport` supports wheel input, Page Up/Down, Home/End, scrollbar dragging, and focus reveal. Overlays keep different input ownership explicit: a tooltip is informational, a persistent callout remains in layout flow, a dialog is a separate blocking `Screen`, and a toast is queued non-blocking feedback. The UI Kit owns presentation and local interaction state only; consumers remain responsible for domain state and server authority.

`CobblemonUiRenderSlot` currently renders a generic texture, an item stack, or a built-in player model using a supplied skin. It does not yet provide a generic Cobblemon Pokemon or Bedrock-model renderer. `CobblemonUiPanel` draws a themed section background and title; it does not own, lay out, or clip child widgets.

Button text shadow is off by default. A theme may opt a style in with `UiButtonStyle.textShadow`, and a call site may explicitly override it with `UiButtonSpec(textShadow = true)` or `false`. Prefer the shadow-free default on opaque UI surfaces.

`UiBorder.PixelFrame` keeps its hard shadow close to the widget with a default logical offset of `1`. Themes may override `shadowOffset`; `pixel_league` uses `2` for the outer shell and `1` for cards and controls so the screen hierarchy remains visible without making each widget look detached.

This module does not replace vanilla inventory, crafting, chat, or other general Minecraft screens. Its final distribution form remains undecided until both League Challenge and Cobblemon Battle UI consume the runtime contract.
