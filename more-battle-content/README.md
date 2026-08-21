# Cobblemon: More Battle Content

Adds Battle Tower, Battle Factory, player-versus-player battles, holographic battle terminals, BP progression, and shared battle presentation to Cobblemon.

- Mod ID: `cobblemon_more_battle_content`
- Side: client and server
- Requires: Fabric API, Fabric Language Kotlin, Cobblemon 1.7.3, and Mega Showdown 1.9.3

## Build

Run from the repository root:

```powershell
.\gradlew.bat :more-battle-content:build
```

The JAR is written to `more-battle-content/build/libs`.

Module-specific maintenance utilities are kept in [`tools`](tools/).

## Data pack catalogs

Battle Tower and Battle Factory data are loaded independently from every JSON file in these server-data directories:

- `data/<namespace>/battle_tower/opponents/*.json`
- `data/<namespace>/battle_factory/catalog/*.json`

Files in one directory never affect the other facility. Fragments may contain only profiles/trainers or only sets, but all fragments for one facility must use the same schema version. IDs must be unique after merging. A missing, malformed, or conflicting fragment rejects the whole reload and preserves the last valid catalog.
