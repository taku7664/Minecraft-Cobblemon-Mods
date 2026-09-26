# `cobblemon-dev` level-cap validation deployment — 2026-09-27

Target: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev` only. The production server was not changed.

- League Challenge `0.2.3` JAR installed in `mods/`; SHA-256 `3E2CD5097B1A5DA918A0FB96548281D3A02E1CE742EBFD5BB8786B97791297A7`. Built and installed hashes match.
- Previous `0.2.2` JAR was removed from that profile after verifying the new JAR. No other mod JAR was replaced.
- CLC `config/cobbled_level_control/server.toml`: `restrictLeveling=true`, `restrictCatching=true`, `enableScaling=false`.
- CLC leveling and catching tiers 1–10 both read `15,20,30,40,45,50,55,60,70,100`. The existing `scalingMethod` remains `+- random7` but is inactive while `enableScaling=false`.
- `:more-battle-content-league-challenge:unitTest :more-battle-content-league-challenge:remapJar --offline --no-daemon`: 56/56 League tests passed; build succeeded. This command did not run Better AI tests.
- The installed MBC `1.6.24` JAR contains the League-required `BattleContentAccess` and `ManagedPveBattles` classes. This is a structural compatibility check, not a world-join result.

Not yet verified: world startup, actual CLC tier values after login, distribution of spawned wild levels, capture at cap, badge-driven cap increase, or rejection of a traded over-cap party in the running game. A client restart is required to load the updated JAR and TOML.
