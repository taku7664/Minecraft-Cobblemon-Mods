# Local League verification profile — 2026-09-26

Target: `cobblemon-dev` local client profile. No server deployment. The named
profile was closed before file replacement. The source branch was
`feature/league-systems` at `ec2e3d30` when artifacts were selected.

## Applied files

| Fabric ID | Installed file | SHA-256 |
| --- | --- | --- |
| `cobblemon_more_battle_content` | `cobblemon-more-battle-content-1.6.22.jar` | `8E38BAC9837949578930E380154D85EA3D29AD0EC927828120266566C725F642` |
| `cobblemon_more_battle_content_league_challenge` | `cobblemon-more-battle-content-league-challenge-0.2.1.jar` | `ED32F5403DACB94C53BA95F59267F98AD7229110B0BD6D17897934E309C6F81C` |
| `pokebadges` | `pokebadges-fabric-1.6.1.jar` | `CEA1D0DB1394B504FD5F7538D027CF9076A496B47ECD45955A06979378DD8A08` |

The existing MBC 1.6.21 JAR and original CLC `server.toml` are backed up in
`cobblemon-dev/codex-deploy-backups/league-20260926/`. Existing Better AI 1.2.18,
Cobbled Level Control 1.2.0, Matthiesen Core 1.2.10 and all other profile
JARs remained at their installed versions.

The approved `server.main.levelingModule.tiersConfig` is now numeric tiers
1–10 with levels 15, 20, 30, 40, 45, 50, 55, 60, 70, 100.
`restrictLeveling = true`. Parsed TOML comparison against the backup confirmed
that the leveling tier list was the only config value changed. The approved
reference fragment is [clc-leveling-tiers.toml](../../../tools/league-test-world/clc-leveling-tiers.toml).

The new `saves/league-cap-test` world was prepared from disposable development
chunks with no player, player-owned Pokemon storage, CLC account or League progress records copied.
Its world-local datapack gives a six-Pokemon test party and candies on first
join and places a terminal at spawn. See [world instructions](../../../tools/league-test-world/README.md).
The existing `새로운 세계` save and Lv50 party were not edited.

## Evidence and remaining check

- Previous same-source MBC/League/UI Kit build and 1,088 unit tests passed.
- The three release JARs passed `jar --validate` and complete ZIP-entry reads;
  installed SHA-256 values equal source artifact values.
- Profile `fabric.mod.json` inventory shows required direct dependencies,
  no duplicate top-level Fabric IDs, and no `.deploying` residue.
- The test world's `level.dat` has no embedded `Player` and enables commands
  and the world-local test pack. Profile CLC TOML parses with the approved caps.

The installed profile and new world have not yet been launched together;
first-join items, screen opening, battle results and cap behavior require
live testing. The profile's `logs/latest.log` still ends at the pre-deployment
client shutdown. This record is a deployment/structural check, not gameplay proof.

## First in-world finding (later on 2026-09-26)

The user entered `league-cap-test`. `logs/latest.log` showed the active League
catalog loading and the setup messages, but six `givepokemon` calls failed with
the missing-species error. The likely cause is that ordinary function commands
were parsed before Cobblemon species were ready. The fixture now calls a function macro that
parses `givepokemon` at execution time; that correction has not yet been run
in-game. The user's manually given six Pokemon were left unchanged.

The CLC world record had leveling tier 1 (cap 15), and the log displayed CLC
leveling-tier rejections while candies were tried. The Lv10 Charmander stopping
at 15 matches the hard growth cap. Lv30 Pokemon kept showing Lv30 because this
release has no virtual effective-level projection. Display and battle-level
projection are a separate implementation task; the original deployment record
above must not be read as proof that they exist.

## Startup linkage repair (later on 2026-09-26)

The client subsequently started with MBC 1.6.23 and League 0.2.1. The installed
MBC JAR lacked `api/access/BattleContentAccess.class`, so League's server-start
registration threw `NoClassDefFoundError` before the world could open. The
secondary CLC shutdown NPE followed the aborted startup. Latest `main` MBC
changes were merged with the League API and built as MBC 1.6.24; League 0.2.2
now requires that version. Both remapped JARs were deployed to `cobblemon-dev`
after the client exited. Prior JARs were moved to
`codex-deploy-backups/league-20260926-crash-hotfix-2315`. The installed MBC
JAR contains the missing class and the installed League manifest requires
MBC 1.6.24 or newer. This is binary/deployment evidence only; successful world
startup and gameplay still require a client relaunch.
