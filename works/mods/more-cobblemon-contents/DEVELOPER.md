# MCC 개발자·운영 참고

A core mod and content mods for Cobblemon battle facilities.

| Module | Mod ID | Contents |
|---|---|---|
| `more-cobblemon-contents` | `more_cobblemon_contents` | Core: managed battle engine, built-in Better AI, Battle Points and shop, records, battle hub, presentation, holo terminal, `/mcc` |
| `more-cobblemon-contents-battle-tower` | `more_cobblemon_contents_battle_tower` | Battle Tower |
| `more-cobblemon-contents-battle-factory` | `more_cobblemon_contents_battle_factory` | Battle Factory |
| `more-cobblemon-contents-pvp` | `more_cobblemon_contents_pvp` | PvP rooms and the battle lounge |
| `more-cobblemon-contents-league-challenge` | `more_cobblemon_contents_league_challenge` | League Challenge: gyms, the Pokemon League and level caps |

## Terminals and hub tabs

Each content mod registers a hologram terminal through `HoloTerminals.register` (`api.terminal`): an ID, its
default tabs and a colour palette. Like `/mcc`, a terminal opens the hub on the dashboard when it is listed. The server reads which tabs each way into the hub shows
from `config/more-cobblemon-contents/hub_tabs.json`, written with the defaults on first start and read again on
`/reload`:

```json
{
  "command": ["more_cobblemon_contents:dashboard", "more_cobblemon_contents:shop", "more_cobblemon_contents:pvp"],
  "command_permission_level": 0,
  "terminals": {
    "more_cobblemon_contents_league_challenge:league_terminal": [
      "more_cobblemon_contents:dashboard", "more_cobblemon_contents:shop", "more_cobblemon_contents:league_challenge"
    ]
  }
}
```

`command` is `/mcc`; `command_permission_level` is the level `/mcc` needs to open the hub (0, the default, lets
every player; 2 only operators). Under it players have only `/mcc bp` and `/mcc bp history [count]` for their own
BP; every other command needs level 2. `terminals` is keyed by
terminal block ID. A missing entry takes its default, and a broken one
falls back to its default with a warning in the log. The server refuses to open a content the hub was not opened
with, so a tab left out cannot be reached by a modified client either.

## Dashboard cards

The hub dashboard shows one card per content. A content registers a section for its own content ID on the server
and builds its card for the player whose hub is opening; the core draws every card the same way, taking the icon
and order from that content's hub tab:

```kotlin
MccDashboardSections.register(ManagedBattleContentIds.BATTLE_TOWER) { context ->
    val records = context.records(ManagedBattleContentIds.BATTLE_TOWER)
    MccDashboardCard(contentId, title, stats = listOf(MccDashboardStat(label, value)), rows = MccDashboardCards.recordRows(records))
}
```

A card has up to four stats, up to sixteen rows (title, value, optional detail) and an optional note. A content
the player cannot open yet gets its access denial as the note. Contents with records but no section get a plain
card from `MccDashboardCards.records`, and a section that fails falls back to that card too.

## Client context for music and other client mods

A client mod can learn where the player is in MCC without touching its internals. `MccClientContext.current()`
returns the hub tab while the hub is open (`hubTab`, null otherwise) and the battle on screen (`battle`, with its
`MccBattleTag`). `MccClientContext.listen { previous, current -> }` is told once per change, checked every client tick:
hub opened or closed, tab switched, battle started, tagged or ended.

A battle's tag names the content that runs it, its stage and, where there is one, the opponent:

| Content | `contentId` | `stage` | `opponentId` |
|---|---|---|---|
| League Challenge | `more_cobblemon_contents:league_challenge` | `gym`, `elite_four`, `champion`, and `hard_gym`, `hard_elite_four`, `hard_champion` on the hard route | challenge id, e.g. `more_cobblemon_contents_league_challenge:cynthia` |
| Wild trainers | `more_cobblemon_contents:league_challenge` | `wild_trainer`, `wild_trainer_ace` | NPC class |
| Battle Tower | `more_cobblemon_contents:battle_tower` | `regular`, `tier_boss`, `master_ball_boss` | trainer profile id |
| Battle Factory | `more_cobblemon_contents:battle_factory` | `regular`, `factory_head` | none |
| PvP | `more_cobblemon_contents:pvp` | `single`, `double` | none |

Hub tab ids are the content ids above, plus `more_cobblemon_contents:dashboard` and `more_cobblemon_contents:shop`.
Battles no content tagged (wild Pokémon, plain challenges between players) have a null tag.

On the server, MCC tags the battles it runs. A content tags any other battle it starts by starting it inside
`MccBattleTags.during`; the tag reaches the clients before Cobblemon's first battle packet:

```kotlin
MccBattleTags.during(setOf(player.uuid), MccBattleTag(CONTENT_ID, "wild_trainer", trainerId)) {
    BattleBuilder.pvn(player, npc, leadId, BattleFormat.GEN_9_SINGLES, false, false, party)
}
```

`ManagedPveBattles.Request` takes the stage directly (`stage = "gym"`) and uses its trainer id as the opponent.

## Operator commands

These need permission level 2; `/mcc` itself follows `command_permission_level`, and players keep `/mcc bp` and
`/mcc bp history [count]` for their own BP. Player arguments that read or edit saved data take offline
players too.

| Command | What it does |
|---|---|
| `/mcc` | Opens the hub with the tabs `hub_tabs.json` gives the command |
| `/mcc status` | Storage health, the shop and every content's catalog, sessions, battles and waiting results |
| `/mcc records reset <player> [content] [format]` | Deletes records; refused while the player has a run, battle or waiting result |
| `/mcc battle list` | MCC battles in progress |
| `/mcc battle end <player> forfeit\|void` | Forfeit (a loss; in PvP the other player wins) or end without a result |
| `/mcc battle pending [list\|retry\|drop] [player]` | Results every content is still retrying to save |
| `/mcc bp …` | Balances and history, add, remove, set |
| `/mcc tower streak …`, `/mcc tower session <player>`, `/mcc tower abandon <player> [force]` | Streaks, a session, ending it (force drops a session whose battle is gone) |
| `/mcc factory floor …`, `/mcc factory session <player>`, `/mcc factory abandon <player> [force]` | The same for Battle Factory runs |
| `/mcc pvp rooms`, `room close\|kick <player>`, `challenge cancel <player>`, `arena list\|release <index>`, `lounge rescue <player>` | Rooms, challenges, arena slots and the lounge |
| `/mcc league inspect\|rewards list\|rewards retry\|rewards drop\|run cancel <player>` | Progress, undelivered rewards (drop marks them delivered without awarding) and runs |
| `/mcc league cap sync <player>`, `validate`, `catalog`, `import-badges <player>` | Level cap, setup check, catalog state, badge migration |
| `/mcc league trainer spawn [kind\|random] [count] [pos]`, `despawn [radius]`, `list [radius]`, `cooldown reset <player>` | Wild trainers |

Contents take part in `status`, `battle pending` and the record reset check through `MccAdminSources`.

## Server wiki

With `enabled` set in `config/more-cobblemon-contents/wiki.json`, the server serves the wiki (the repository's
`server-wiki/`, copied into `directory`) over HTTP while it runs, and `/api/me` answers with the asking player's
BP, records and content sections, read fresh on every request:

```json
{ "enabled": true, "bind": "0.0.0.0", "port": 8100, "public_url": "http://play.example.com:8100",
  "directory": "config/more-cobblemon-contents/wiki" }
```

It is off by default, since it opens a port. `public_url` is the address players' browsers reach; without it links
point at `http://localhost:<port>`. A player's link carries their token, which the wiki keeps in the browser and
sends with each `/api/me` request. The core has no command for links: the server's own mod hands them out with
`WikiApi.linkFor(playerId)`, and `WikiApi.resetLinkFor(playerId)` issues a new token that ends the old links (on
this server, jbro-policy's `/wiki`). Tokens live in the world's `data/mcc_wiki_tokens.json`.
Contents add their own data to `/api/me` with `WikiPlayerData.register(key) { server, playerId -> json }`.
They add endpoints of their own with `WikiApi.register("pvp/matches") { request -> json }`, answered on the wiki's HTTP
threads under `/api/<name>`; `request.viewer` is the player whose token came with the request, if any.

All modules share the package root `jbro.cobblemon.mcc`. Versions live in the root `gradle.properties`
(`more_cobblemon_contents_version`, `more_cobblemon_contents_battle_tower_version`, ...).

## Build and test

```bash
./gradlew :more-cobblemon-contents:unitTest
./gradlew :more-cobblemon-contents:remapJar
```

`tasks.test` is disabled; run tests with `unitTest`. In the core, `-Pscope=core` or `-Pscope=ai` runs one
half of the suite. Build the module jars one `remapJar` at a time.

Work history, decisions and open issues are recorded in [`MEMORY.md`](MEMORY.md).
