# League production UI wiring v1

- Date: 2026-09-26
- Audience: League maintainer and UI Toolkit/Battle UI maintainers.
- Updates: [SYSTEM_IMPLEMENTATION_V1.md](SYSTEM_IMPLEMENTATION_V1.md), client UI handoff/status only. Server progression and datapack contracts remain unchanged.
- Status: implemented; see verification below for the evidence boundary.

## Ownership and behavior

`LeagueHomeScreen` consumes UI Kit panels, text, callouts, gym step track, scroll viewport,
button variants and confirmation dialog. It does not fork the toolkit or replace Battle UI.
The client keeps selection and in-flight intent only; badge count, rank, cap, BP, challenge
availability and run phase MUST come from server snapshots.

- Terminal right-click sends a server snapshot with `openScreen=true`; normal background updates MUST NOT open the screen.
- The screen exposes Challenge, Continue, Forfeit, Refresh and Close. Forfeit requires confirmation. Close/Escape MUST NOT cancel a run.
- Start/Next remain subject to existing terminal/session/revision checks, CLC validation and server progression rules. Disabled client controls are presentation, not authorization.
- Result-triggered open waits for the battle UI to end and for other screens to close. Requests expire after 200 client ticks, are consumed once, and are discarded on disconnect, session replacement or explicit close.
- Pending intent disables repeat submission. A 100-tick timeout permits retry/refresh without granting progress locally.
- UI copy is provided in `en_us` and `ko_kr`. Custom league/trainer names remain resource-pack translation keys.

`LeagueView` adds optional `openScreen` and `runNameKey` fields on the existing JSON state channel.
The old development fixture command remains separate. Older League clients can ignore these new
fields but cannot display the production home; update client and server together for this release.

## Better AI connection retained

Main commits `c03987a5` (managed AI test entrypoint), `edd11827` (unbounded decision option)
and `eacf8a78` (central lifecycle) are retained. The addon adapter delegates to
`Cobblemon173TowerPveBattleRuntime.startManaged(Cobblemon173ManagedAiBattle)`.
AI test, Tower and League therefore use the lifecycle-owned runtime; League does not introduce
a second entity cleanup implementation. `unboundedBrainDecision` is still forwarded to the brain
actor, and register/end/abort cleanup remains present. League adds optional appearance and
nullable preview/mechanic inputs without deleting the AI-test constructor fields.

The merge baseline is main `238fb166`; concurrent uncommitted Better AI work was not included.

## Packaging

League provisionally nests the client-only `cobblemon_ui_kit` JAR. This is a delivery choice,
not a final decision to require a separately installed toolkit. The universal League mod MUST NOT
declare a hard dependency on a client-only mod: dedicated servers do not load it. Client-only
widget references stay in League's client package. MBC, PokeBadges and CLC remain external,
required dependencies. The dev runtime uses same-workspace named artifacts, avoiding remapping
an old release JAR as though it were the current source.

## Verification and remaining work

The integration build passed MBC 981 tests, League 53 tests and UI Kit 54 tests, plus Better AI
compilation. The remapped League JAR contains `META-INF/jars/cobblemon-ui-kit-0.1.0.jar`.
The integrated-world terminal/home/scroll/focus/Refresh/Close smoke test also passed;
[dated evidence and captures](evidence/LIVE_UI_SMOKE_2026-09-26.md) record its exact limits.
Two existing MBC snapshot tests required explicit species registration; they now restore the
previous registry after execution instead of depending on a different test running first.

Development startup also requires MBC's Mega Showdown/Architectury/Accessories companions and
CLC's Forge Config API Port. Loom's remapped Modrinth artifact omitted the nested Night Config
libraries from the development classpath; matching `core`/`toml` 3.8.0 are explicit runtime-only
dependencies. These development dependencies are not newly bundled into the League JAR.

Pure state tests cover locked challenges, pending rewards, duplicate input, server rejection,
selection retention and disconnect. Routing tests cover no implicit open, battle/other-screen
deferral, expiry and one-shot consumption. MBC source-contract coverage protects the shared
AI entrypoint, unbounded flag and cleanup calls; it is not a live AI battle test.

The opt-in development harness `MBC_LEAGUE_CAPTURE_LIVE=1` only permits a copied world directory
named `league-wiring-smoke`. It places one terminal in an empty adjacent block, sends a normal
client block-use interaction, waits for the real server snapshot, captures the production home,
tests scroll/focus, refresh acknowledgement and close, then exits. It MUST NOT run against an
existing user world. It does not fabricate wins or bypass CLC checks.

Whole-league completion, live Better AI battles, disconnect/restart during a live fight,
dedicated-server boot, and named-profile deployment are not established by unit tests or JAR builds.
No authoritative client/server CLC configuration was changed. The configured cap table and
required badge provider still need installation/configuration checks before gameplay acceptance.
