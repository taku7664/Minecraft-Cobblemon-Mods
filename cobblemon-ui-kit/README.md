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

`COBBLEMON_UI_KIT_CAPTURE_WORLD=1` is reserved for automated capture. It deliberately exercises focus, scrolling, close, and then stops the client; do not use it for manual review.

The command is not registered outside Fabric's development environment. The gallery demonstrates semantic button variants and sizes, content/fill widths, widget states, a scrollable list surface, progress styling, and composable rectangle/chamfer, solid/gradient fill, optional border, and background-opacity styles in English and Korean.

This module does not replace vanilla inventory, crafting, chat, or other general Minecraft screens. Its final distribution form remains undecided until both League Challenge and Cobblemon Battle UI consume the runtime contract.
