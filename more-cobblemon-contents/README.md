# More Cobblemon Contents (MCC)

A core mod and content mods for Cobblemon battle facilities.

| Module | Mod ID | Contents |
|---|---|---|
| `more-cobblemon-contents` | `more_cobblemon_contents` | Core: managed battle engine, built-in Better AI, Battle Points and shop, records, battle hub, presentation, holo terminal, `/mcc` |
| `more-cobblemon-contents-battle-tower` | `more_cobblemon_contents_battle_tower` | Battle Tower and the Cynthia AI test (`/mcc test`) |
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
| `/mcc test ai-… \| stop [player]` | Cynthia AI test battle, and ending it |
| `/mcc tower streak …`, `/mcc tower session <player>`, `/mcc tower abandon <player> [force]` | Streaks, a session, ending it (force drops a session whose battle is gone) |
| `/mcc factory floor …`, `/mcc factory session <player>`, `/mcc factory abandon <player> [force]` | The same for Battle Factory runs |
| `/mcc pvp rooms`, `room close\|kick <player>`, `challenge cancel <player>`, `arena list\|release <index>`, `lounge rescue <player>` | Rooms, challenges, arena slots and the lounge |
| `/mcc league inspect\|rewards list\|rewards retry\|rewards drop\|run cancel <player>` | Progress, undelivered rewards (drop marks them delivered without awarding) and runs |
| `/mcc league cap sync <player>`, `validate`, `catalog`, `import-badges <player>` | Level cap, setup check, catalog state, badge migration |
| `/mcc league trainer spawn <kind>`, `despawn [radius]`, `list [radius]`, `cooldown reset <player>` | Wild trainers |

Contents take part in `status`, `battle pending` and the record reset check through `MccAdminSources`.

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
