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
- MUST update keyboard focus coordinates and mouse hitboxes with every resized/repositioned tile. Up/down navigates the command and move column; left/right/up/down navigates the 2×3 party grid; confirm activates exactly the focused item; Escape/cancel goes back only when allowed. Forfeit confirmation needs its own two-choice keyboard navigation and must not submit on a repeated keypress.
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
4. **Verification:** unit/contract tests for geometry, state mapping and keyboard navigation, build, startup with actual mixin application, client screenshot of each live surface and mouse/keyboard paths per actionable screen. Specifically test expanded move/party hitboxes against their rendered bounds, skipped disabled/fainted/currently-active tiles, 2×3 directional movement, forfeit focus/confirm/cancel, and no accidental double-submit. A static preview or successful build alone does not prove gameplay behavior.
5. **Release:** deploy client-only JAR to the named `cobblemon-dev` profile, compare SHA-256, keep server untouched, commit/push the verified logical change, and distinguish untested gameplay explicitly if any route cannot be exercised.

## Risks to check before implementation

- The existing `BattleOptionTile` and `MoveTile` render hooks are visual-only, but switch currently only scales the native tile. Forfeit has a separate `ForfeitConfirmationSelection` with `BattleResponseButton`s, and the top HUD belongs to `BattleOverlay`, not `BattleGUI`.
- Cancelling the entire top HUD renderer could accidentally hide portraits, ball/status indicators, animation, or multiplayer slots. Inspect its exact 1.8.1 parameters and draw order; consider replacing only the native background while retaining native content if that preserves all states.
- UI from Battle Extras, Mega Showdown, and Extended Battle UI can overlap. Capture with the named profile's active mod set and confirm overlay order before deployment.

## Static draft review (not live battle verification)

- The opt-in development client entered a generated world and saved draft pages at 1280×900 and 854×480. The compact Korean capture was compared against the current `battle-ui-final-readability-controls-ko_kr.png`: the existing page shows only four colored buttons and four narrow moves, while the draft adds paired top HUDs, a larger move list, a 2×3 party panel, and a dedicated confirmation panel.
- The first 92×24 move draft left insufficient room for name, PP and type/category glyphs. The revised proposal uses 140×32 and puts native Cobblemon type/category icons **inside** each tile. Production MUST update layout, hover hitbox and tooltip bounds together.
- The first party draft omitted portrait and level. The revised party card is 118×34 with a model portrait, level, name and HP gauge. Production MUST maintain active/fainted/forced-switch semantics and expand the hitbox together with the card.
- At 854×480 the dimmer command variant made the world hand visible through its lower actions. The chosen direction is the **rail** variant with a bright primary Fight tile and high-opacity, nearly borderless secondary actions. The full-gradient blade variant remains a reviewed alternative, but is too close to the existing four same-shaped colored tiles.
- The single HUD draft is 184×50, and the double/triple compact version is 184×28 per slot with 31 px row spacing. The third compact row stops above the current bottom-right command column at GUI scale 2. Live rendering still needs a collision check with BattleCam and battle info overlays.
- The centered forfeit draft is now 220×88, with two 68×24 actions; the native confirmation is 113×45. Production MUST not draw a larger visual button while keeping only the tiny native click target. Mouse, confirm and cancel semantics must follow the new geometry.

## Independent design review and revision

- A second reviewer inspected the seven Korean 854×480 draft screenshots against the previous control capture. The reviewer selected the rail command variant; its bright Fight/quiet secondary distinction is clearer than the four similarly framed blade tiles. This is visual feedback, not a gameplay test.
- The reviewer found that compact double/triple HUDs lacked HP numerals and status, party cards did not label active/fainted states or Back, and the forfeit panel had too much blank space and excessively wide buttons. The next draft added compact HP numbers and a burn example, labeled active/fainted/burned party entries and Escape, and shortened the confirmation panel to 220×88 with 68 px actions.
- The second review compared all seven v5 pages in both `ko_kr` and `en_us` at 854×480. The forfeit panel was 13 physical pixels below the HUD rather than intersecting it. The reviewer identified three remaining issues: the party panel ended only 5 px above the window edge; lower command buttons let the world/hand show through; and a disabled move was too dark to read. The v6 proposal reduces party height 155→150 and cards 36→34, makes rail secondary fills opaque, and raises disabled move text contrast. These need a new screenshot review, then a production-vs-draft comparison.
- The independent v6 review confirmed the revised party panel ends about 17 px above the window edge, the command rail is opaque enough to separate it from the world, and the disabled move remains readable. The static party panel still intersects the ordinary hotbar; only an actual battle capture can establish whether this is a gameplay overlap.
- A v7 preview switched command, move, switch, and forfeit pages to their shared production renderers. Side-by-side 854×480 Korean comparison against v6 found matching panel and control positions and proportions; the changing world scene is not a UI difference. An initial reviewer report of missing Korean icon/level/forfeit glyphs in v7 was **withdrawn after rechecking the original files side by side**: all three elements are visible in both v7 and the repeated v8 capture. The reviewer also compared the suspect pixel regions: the first move icon contains 236 bright blue pixels in each image, and the forfeit title/body text regions contain 544/658 bright pixels in each. There is no demonstrated renderer regression in these captures. This is a renderer/draft comparison, not a live battle screenshot or interaction test.
- The move list now has a 16 px bottom inset; ordinary Korean and English sample names fit. Long nicknames, long move names, live status/HP mapping, actual hitboxes, and navigation remain unverified. A matching-size live-battle screenshot and interaction test are required before calling the redesign implemented.
