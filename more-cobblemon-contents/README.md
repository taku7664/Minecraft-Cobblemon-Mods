# More Cobblemon Contents (MCC)

A core mod and content mods for Cobblemon battle facilities.

| Module | Mod ID | Contents |
|---|---|---|
| `more-cobblemon-contents` | `more_cobblemon_contents` | Core: managed battle engine, built-in Better AI, Battle Points and shop, records, battle hub, presentation, holo terminal, `/mcc` |
| `more-cobblemon-contents-battle-tower` | `more_cobblemon_contents_battle_tower` | Battle Tower and the Cynthia AI test (`/mcc test`) |
| `more-cobblemon-contents-battle-factory` | `more_cobblemon_contents_battle_factory` | Battle Factory |
| `more-cobblemon-contents-pvp` | `more_cobblemon_contents_pvp` | PvP rooms and the battle lounge |
| `more-cobblemon-contents-league-challenge` | `more_cobblemon_contents_league_challenge` | League Challenge: gyms, the Pokemon League and level caps |

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
