# Cobblemon: More Battle Content - Better AI

Optional server-side tactical AI for Cobblemon: More Battle Content. It keeps external-provider and local-brain decision paths separate and falls back safely when a provider is unavailable.

- Mod ID: `cobblemon_more_battle_content_better_ai`
- Side: server only
- Requires: Cobblemon: More Battle Content

## Build

Run from the repository root:

```powershell
.\gradlew.bat :more-battle-content-better-ai:build
```

The JAR is written to `more-battle-content-better-ai/build/libs`.

## Local AI baseline capture

This opt-in test tool records complete sampled teams, battle seeds, difficulty,
tuning, source/runtime SHA-256 fingerprints, and actual local-brain decisions with
elapsed times. It uses the existing local projector self-play harness, not an
independent battle engine or a live server. It does not call OpenRouter; decision
source `LOCAL_BRAIN_DIRECT` does not measure provider fallback behavior.

Run from the repository root (PowerShell):

```powershell
.\gradlew.bat :more-battle-content-better-ai:captureBaseline --no-daemon `
  -PbaselineBattles=2 -PbaselineSeed=20260905 -PbaselineTurns=30 `
  -PbaselineFormat=SINGLE -PbaselineTier=STANDARD `
  '-PbaselineOutput=more-battle-content-better-ai/build/reports/betterai-baseline/example'
```

Those are the defaults, except that omitting `baselineOutput` creates a unique
directory. Formats are `SINGLE` and `DOUBLE`; tiers are `INTRODUCTORY`, `STANDARD`,
`ADVANCED`, and `BOSS`. Battle count is 1–10,000; turn limit is 1–1,000. Each battle
draws a new team sample from the harness's supported complete rental sets; repeats
are possible. The opponent follows the harness's fixed offense strategy.

Each run writes:

- `manifest.json`: ordered team definitions, complete referenced sets, settings,
  roster fingerprint, Git revision and source/runtime fingerprints.
- `battles.jsonl`: one flushed record per completed battle, containing its result
  and local-brain decision trace. Durations are nanoseconds.
- `summary.json`: completion marker and basic runtime metadata. A failed or
  interrupted run has no completion marker; incomplete results are not a baseline.

Existing output directories are never overwritten. Outputs belong under `build/`
and are not committed. Server configuration and environment variables are not
exported. Source fingerprints include both MBC modules' sources and build inputs;
runtime fingerprints cover the actual test classpath, including rule/data assets.

Replay into a **new** directory:

```powershell
.\gradlew.bat :more-battle-content-better-ai:captureBaseline --no-daemon `
  '-PbaselineReplay=more-battle-content-better-ai/build/reports/betterai-baseline/example/manifest.json' `
  '-PbaselineOutput=more-battle-content-better-ai/build/reports/betterai-baseline/example-replay'
```

Replay uses saved options and refuses changed teams, settings, sources or runtime
contents. It is a same-input replay, not a cross-version comparison tool: timed
search can still choose differently under different machine load. Wins in this
harness do not establish real-game strength or independent mechanics correctness.
