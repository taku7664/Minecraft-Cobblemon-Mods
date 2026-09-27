# Battle screen redesign (2026-09-28)

Status: draft. This records a visual direction and verification gates, not a claim that the live battle UI has changed.

## References and decisions

- [Pokémon Legends: Arceus official gameplay](https://legends.arceus.pokemon.com/en-gb/gameplay/): battle stays in the world. Adapt the low-obstruction composition, not its art.
- [Nintendo eShop battle screenshot](https://ec.nintendo.com/HK/zh/titles/70010000039948): HP and move/PP information remain legible while the battlefield occupies most of the view.
- [Pokémon Battle UI concept](https://dribbble.com/shots/26306212-Pok-mon-Battle-UI): selective elemental color and tactile emphasis. Use type color as a narrow accent, not as a full saturated button background.
- [Cobblemon Extended Battle UI gallery](https://modrinth.com/mod/cobblemon-extended-battle-ui/gallery): existing tactical information density is useful, but the command view should not grow another always-open information pane.

No third-party artwork or resource-pack assets will be copied. Native Minecraft/Cobblemon rendering and the mod's `BattleSurfaceRenderer` will draw the visual layer, so a separate resource pack is not required.

## Scope and invariants

- MUST restyle command choices (Fight, Pokémon/Switch, Bag/Catch, Run/Forfeit), four moves, both sides' top Pokémon HUD, the party switch selection, and the forfeit confirmation. The bag/catch choice must remain present when Cobblemon presents it; the menu must not assume exactly three actions.
- MUST preserve Cobblemon's action requests, hit targets, disabled/fainted/currently-active states, mouse and keyboard navigation, escape/back, focus order, forced switch, double/triple battle slots, and actual forfeit response. Visual rendering must not send responses from the preview.
- MUST keep the native option/move tile dimensions unless the hitbox and navigation layout change together. Current Cobblemon 1.8.1 sizes: option 90×26, move 92×24, switch 94×29, forfeit confirmation 113×45 (from the installed dependency JAR).
- MUST keep player/self on the left and opponent on the right in our preview, and verify the live battle side mapping before replacing native HUD drawing.
- MUST localize new user-facing copy in `en_us` and `ko_kr`, keep labels readable without text shadows at GUI scale 2, and check long move/species names.
- SHOULD use clipped diagonal cuts as a directional accent: main action has leading left cut; secondary actions use quieter borderless/background-only states; danger actions are visibly different. Do not make every tile the same rectangle with a different color.
- SHOULD keep HP, name, level and status independent of the command selection state. Type color is only an accent; readability must not rely on hue alone.
- MAY offer a configuration switch back to native HUD if the complete overlay cannot be safely replaced across other client mods. The existing ModMenu/config screen is the customization path.

## Sequence and evidence gates

1. **Static in-client draft:** opt-in development capture renders full scene pages for two command compositions, moves, top HUD, switch and forfeit without action callbacks. Save `ko_kr` and `en_us` screenshots at normal and compact GUI sizes. Compare side by side against the current capture and real Cobblemon dimensions.
2. **Shared visual components:** settle one composition and implement renderer functions for command, move, HUD, switch row and confirmation. Preview must call the same renderers as production, with sample data only.
3. **Live integration:** inject only at the corresponding Cobblemon drawing sites; leave request/selection handlers owned by Cobblemon. Include status/fainted/disabled, double/triple and overflow cases.
4. **Verification:** unit/contract tests for geometry and state mapping, build, startup with actual mixin application, client screenshot of each live surface and at least one click/keyboard path per actionable screen. A static preview or successful build alone does not prove gameplay behavior.
5. **Release:** deploy client-only JAR to the named `cobblemon-dev` profile, compare SHA-256, keep server untouched, commit/push the verified logical change, and distinguish untested gameplay explicitly if any route cannot be exercised.

## Risks to check before implementation

- The existing `BattleOptionTile` and `MoveTile` render hooks are visual-only, but switch currently only scales the native tile. Forfeit has a separate `ForfeitConfirmationSelection` with `BattleResponseButton`s, and the top HUD belongs to `BattleOverlay`, not `BattleGUI`.
- Cancelling the entire top HUD renderer could accidentally hide portraits, ball/status indicators, animation, or multiplayer slots. Inspect its exact 1.8.1 parameters and draw order; consider replacing only the native background while retaining native content if that preserves all states.
- UI from Battle Extras, Mega Showdown, and Extended Battle UI can overlap. Capture with the named profile's active mod set and confirm overlay order before deployment.
