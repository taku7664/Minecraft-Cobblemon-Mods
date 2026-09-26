# League level, catching, and ordinary wild-spawn policy

Updates: `SYSTEM_IMPLEMENTATION_V1.md` § CLC integration. This is the current policy for League-enabled worlds; the earlier statement that catching and wild spawning remain wholly CLC-owned is superseded.

## Contract

- League Challenge MUST synchronize both CLC leveling and catching tiers to the player's current League cap. The League catalog remains authoritative for that cap.
- CLC `restrictLeveling` and `restrictCatching` MUST both be `true`. CLC `enableScaling` MUST be `false`; otherwise two spawn scalers could compete. Missing tier mappings or conflicting switches MUST reject a new League challenge.
- For a player-caused, unowned Cobblemon wild spawn, League Challenge MUST set the level to `max(1, cap - randomInteger(0, 10))`, with both endpoints included. With cap 15, ordinary wild levels are 5–15. Other spawn paths are not covered by this event policy.
- A League challenge MUST reject a party containing any Pokémon above the current cap, including Pokémon obtained by trade or commands. The existing `ManagedPveBattles.snapshotParty` check enforces this at run entry; a finals run uses that locked party for subsequent opponents.
- This policy does not downlevel obtained Pokémon or alter their original level. High-level Pokémon remain usable outside League subject to the other installed mods' rules.

## CLC configuration for the built-in Sinnoh catalog

In `config/cobbled_level_control/server.toml`:

```toml
[server.main.catchingModule]
restrictCatching = true

[[server.main.catchingModule.tiersConfig]]
tier = "1"
level = 15
# Repeat tiers 2–10 with levels 20, 30, 40, 45, 50, 55, 60, 70, 100.

[server.main.levelingModule]
restrictLeveling = true
# Leveling tiers 1–10 use the same levels as catching tiers.

[server.main.scaling]
enableScaling = false
```

The full sequence is `15, 20, 30, 40, 45, 50, 55, 60, 70, 100` for **both** tier lists. A replacement League data pack MAY use different caps, but its route MUST map exactly to CLC tiers before challenges can start.

The local `cobblemon-dev` profile was selected for validation only. The production server configuration is not changed by this document. If League Challenge is removed, administrators SHOULD re-evaluate CLC `enableScaling`, because League's replacement spawn handler will no longer run.
