# Production League home: integrated-world smoke evidence

Date: 2026-09-26, 16:11 KST. League 0.2.1, MBC 1.6.22, UI Kit 0.1.0,
Minecraft 1.21.1 / Cobblemon 1.8.1. English, 1280×800 window, GUI scale 2.

The development harness operated in `league-wiring-smoke`, a copy of the UI Kit test world.
The authoritative `cobblemon-dev` profile, server and source test world were not modified.
This is programmatic input through the actual game interaction/UI methods, not physical mouse evidence.

Observed sequence:

1. 16:11:39 — integrated server started; 16:11:43 — player joined.
2. 16:11:44 — normal client block-use interaction sent at `(5, 127, -5)`.
3. 16:11:44 — server response opened `LeagueHomeScreen`.
4. 16:11:45 — server snapshot: badges 0, cap 15, error `cap_unmapped`.
5. End-key list scrolling and Tab focus succeeded. A click through `screen.mouseClicked`
   activated the real Refresh button; the server response released the pending state.
6. 16:11:46 — Close returned to the world, no background reopen, progression revision unchanged.
7. 16:11:48 — server shut down normally. Gradle exited successfully; all 53 League tests passed.

The cap mismatch is expected with the unmodified provider defaults. No battle or victory was
fabricated to make the screen appear successful. Challenge/Continue/Forfeit live battle flows,
Korean runtime rendering, dedicated-server startup and full League completion remain unverified.

## Captures

- [Home top](league-live-top-2026-09-26.png)
- [Scrolled challenge list](league-live-scrolled-2026-09-26.png)

Both captures were inspected. The list scrolls inside the shell while header/footer remain in place.
Standard backup/Cobblemon notification toasts cover part of the upper-right area; these captures
do not establish unobstructed final visual acceptance.

## Development launch pitfalls found

- Modrinth's Forge Config API Port artifact requires its nested Night Config libraries explicitly
  on Loom's development classpath. The release dependency already contains them.
- The long Windows classpath crossed Gradle's command-length threshold. Its classpath-manifest
  JAR was not discovered correctly by Fabric. UTF-8 argument files also corrupt the Korean path
  under this JDK's MS949 native encoding. Validation used temporary ASCII junction aliases for
  dependency cache, checkout and output, with Loom's argument-file launch enabled. No source
  or dependency files were moved. This is a development launch workaround, not a shipped runtime patch.
- The harness accepts first-run accessibility and copied-world backup prompts only when explicitly
  enabled. It chooses an empty terminal position from the server player's location because the first
  client world tick can precede the initial player-position packet.
