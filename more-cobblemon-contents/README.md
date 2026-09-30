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

Each content mod registers a hologram terminal through `HoloTerminals.register` (`api.terminal`): an ID, the tab
the hub opens on, its default tabs and a colour palette. The server reads which tabs each way into the hub shows
from `config/more-cobblemon-contents/hub_tabs.json`, written with the defaults on first start and read again on
`/reload`:

```json
{
  "command": ["more_cobblemon_contents:dashboard", "more_cobblemon_contents:shop", "more_cobblemon_contents:pvp"],
  "terminals": {
    "more_cobblemon_contents_league_challenge:league_terminal": [
      "more_cobblemon_contents:dashboard", "more_cobblemon_contents:shop", "more_cobblemon_contents:league_challenge"
    ]
  }
}
```

`command` is `/mcc`; `terminals` is keyed by terminal block ID. A missing entry takes its default, and a broken one
falls back to its default with a warning in the log. The server refuses to open a content the hub was not opened
with, so a tab left out cannot be reached by a modified client either.

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
