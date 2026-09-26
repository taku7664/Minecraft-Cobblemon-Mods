# League system v1 — implementation and UI handoff

- Date: 2026-09-26
- Audience: League server maintainer and the separate UI Toolkit/League screen implementer.
- Updates: [DECISIONS.md](DECISIONS.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), [MBC_CORE_CHANGES.md](MBC_CORE_CHANGES.md), [EXTERNAL_INTEGRATIONS.md](EXTERNAL_INTEGRATIONS.md).
- These planning baselines remain historical. This document describes implemented behavior; it is not a claim of completed gameplay verification.

## Confirmed and provisional decisions

The user explicitly confirmed **Cobbled Level Control** on 2026-09-26. Typed adapters target CLC Fabric 1.2.0, Matthiesen Core 1.2.10 and PokeBadges Fabric 1.6.1. League requires MBC 1.6.22's extension API. PokeBadges and CLC are mandatory loader dependencies.

The first implementation has one active League definition, gym leaders only, six locked party members, full HP/status/PP recovery between fights and no bag items. Disconnect or restart cancels the current run; wins, badges and completed Champion status remain. These operational defaults are implementation choices, not previously finalized balance decisions. No physical gyms or resident trainer NPCs are created.

The bundled modernized Platinum roster uses Roark → Gardenia → Fantina → Maylene → Wake → Byron → Candice → Volkner, followed by Aaron → Bertha → Flint → Lucian → Cynthia. Teams and BP amounts are editable starter content, not a completed balance pass. All trainers initially use a vanilla placeholder skin; original trainer artwork is not included or copied from other packs.

## System boundaries

| Owner | Responsibility |
| --- | --- |
| `system/LeagueEngine` | Pure progression, prerequisites, five-fight run, pinned party/encounters, win/loss and reward receipts |
| `system/LeagueCatalogParser` | Versioned resource schema and complete active reference graph validation |
| `system/TerminalAuthorization` | Pure range, dimension, identity, permission and expiration checks |
| `server/LeagueSavedData` | Per-league/per-player versioned storage; unreadable records are not silently reset |
| `server/LeagueServer` | Server-authoritative intents, result settlement, facility policy, lifecycle and synchronization |
| `server/LeagueIntegrations` | Version-pinned PokeBadges and CLC calls; no reflective command fallback |
| MBC public APIs | Managed PvE, clone safety, BP transactions, content access and trainer projections |
| UI Toolkit/League screen owner | Actual screen, widgets, layout and input presentation |

The runtime initializer now registers the data loader, terminal block/item/entity, networking, lifecycle and administrator commands. `/league-admin inspect <player>` displays status. `/league-admin import-badges <player>` explicitly imports an online player's external gym badges, without BP or a Champion title; both commands require permission level 2. External badges never import themselves.

## Data-pack contract

Definitions live under `data/<namespace>/mbc-league-challenge/{leagues,challenges,trainers,teams,rewards,appearances}/<id>.json`. Every document MUST have `schema_version: 1`.

The active entry is `cobblemon_more_battle_content_league_challenge:active` in `leagues`. Override that file to select different ordered challenges. Its `progress_id` is the save identity. Preserve it to continue the same league; use a new namespaced identity for an independent progression history.

| Kind | Fields |
| --- | --- |
| League | `progress_id`, `name_key`, `initial_cap`, eight `gyms`, five `finals` |
| Challenge | `trainer`, `reward`, optional `badge`, `format` SINGLE/DOUBLE, `mechanic` NONE/MEGA/DYNAMAX/TERA |
| Trainer | `name_key`, `team`, optional `appearance`, `ai_skill` 0–5 |
| Team | `pokemon`: 1–6 explicit Cobblemon PokemonProperties strings; doubles requires at least two |
| Reward | `unlock_cap`, `first_bp`, `repeat_bp` |
| Appearance | optional `skin`: local PNG resource ID, `model`: default/slim |

Names are translation keys. Provide both en_us and ko_kr in the associated resource pack. The built-in resources are examples using the same loader as external packs. Custom species/moves must also exist in the installed Cobblemon content. Structural/reference errors retain the previous catalog; live Pokemon materialization errors reject the battle rather than create a random species. Full registry-semantic validation during reload is not yet implemented.

Active runs pin serialized party members, encounters, trainer settings and reward definitions; `/reload` cannot replace the team in the middle of an Elite Four run. Failed catalog loads do not replace the current structurally valid snapshot. Catalog revisions invalidate stale client requests.

## Level-cap setup

CLC stores a numeric tier, not an arbitrary per-player cap. League checks that all caps in the active route have exact numeric-tier mappings before START/NEXT, then writes the player's current tier through CLC's API and checks it. Disabled leveling restriction, absent storage or an unmapped value locks new challenges. It does not silently select the nearest tier.

The provisional bundled caps are:

| State | Cap |
| --- | ---: |
| Initial | 15 |
| Roark / Gardenia / Fantina / Maylene | 20 / 30 / 40 / 45 |
| Wake / Byron / Candice / Volkner | 50 / 55 / 60 / 70 |
| Elite Four wins | 70 |
| Champion | 100 |

In CLC's `server.main.levelingModule.tiersConfig`, administrators SHOULD define numeric tiers 1–10 with levels `15,20,30,40,45,50,55,60,70,100`, or change League's data to match their chosen tiers. These are not finalized difficulty values. The inspected client currently has only `10,20,40,80,100`; it is not aligned. This implementation has not edited that profile's configuration. Catching, evolution permissions and wild-spawn scaling remain CLC-owned and are not overwritten.

## Persistence and safety

League victory records, not badge items, determine rank and facility access. Win settlement writes progression and stable BP/badge receipts before integration effects. Badge ownership is reconciled from earned gym records. BP delivery reuses each saved transaction identity so retries cannot pay a successful transaction twice. Partial delivery remains pending and blocks the next challenge.

The implementation repairs lagging integration state; it does not claim a transaction across several filesystem saves. Corrupt progress records fail closed and are preserved. Each UTF-8 encoded record uses an NBT byte array (not the 64 KiB-limited NBT string format), has an 8 MiB limit and at most 4096 reward receipts; servers enabling high-frequency repeat BP rewards must account for this current capacity limit. No generic item-reward delivery or reward-ledger compaction is implemented in v1.

Terminal requests MUST come from a live nonspectator player within eight blocks of the same terminal ID in the same dimension, with interaction permission, a matching session nonce and current progression/catalog revisions. Request IDs are deduplicated, traffic is throttled and inactive sessions expire after ten minutes. Clients cannot submit wins, badge counts, rank or caps.

## UI handoff — important

`LeagueClientSession.observe(listener)` publishes a `LeagueView?`; disconnect publishes null. Call `LeagueClientSession.send(action, challengeId)` for START, NEXT, CANCEL or REFRESH. `LeagueView` contains authoritative rank/badges/cap/BP, challenge availability, current encounter, pending reward state and localized rejection key.

The UI owner must attach the real screen to this observer and dispatch these intents. **This branch does not open a production League screen on its own.** The old developer fixture is still separate and is not wired to server state. A terminal packet reaching the client is not evidence that the UI flow is complete. Battle UI and the UI Toolkit have not been reimplemented here. Mod Menu exposes setup guidance; it does not offer client switches that override server rules.

## Build and validation

Use the current named output for the same-workspace MBC dependency (`implementation(project(..., configuration = "namedElements"))`, nontransitive). The former `modImplementation(project(...))` path cached an older remapped MBC JAR under the same release version; the new API existed in the release JAR but was missing from League's compilation classpath. Do not fix this by repeatedly clearing unrelated caches.

The retained owo developer spike needs owo as a `modImplementation` dependency so its injected vanilla widget interfaces are available at compile time. Runtime League behavior does not depend on that spike.

Validation commands:

```powershell
.\gradlew.bat --no-daemon --configure-on-demand :more-battle-content:unitTest :more-battle-content:remapJar :more-battle-content-league-challenge:build
```

Before calling this release-ready, the following remain: UI consumer integration; full in-world win/loss and five-fight completion; duplicate/forged packet tests against a running server; reconnect/restart and reward-failure fault injection; real-party comparison before/after battle; absent-addon Tower/Factory runtime regression; custom-skin/model verification; CLC profile alignment and required-mod deployment. Current tests and JAR builds do not substitute for these checks.

Observed on 2026-09-26: MBC `unitTest` passed 956/956; League `build` passed 46/46 tests and produced the 0.2.0 remapped JAR, including large-record NBT round-trip, capacity and corruption checks. MBC `remapJar` and Better AI `compileKotlin` succeeded. Better AI compilation emitted two nullability warnings in unchanged files. All 62 changed/new JSON resources also parsed independently. Client/server deployment and actual-world verification were not performed.

Storage guards reserve receipt capacity for the entire run before launch. A full history rejects the next challenge rather than allowing an unpersistable victory. Malformed UTF-8 is converted to the same fail-closed error path as corrupt JSON, without resetting the record.
