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

## Paired evaluation and held-out cases

`evaluatePaired` runs every sampled team pair twice. Teams, leads, battle seed,
difficulty and strategy roles stay fixed; challenger and defender tuning swap
between the cycle and offense sides. Execution order alternates between pairs.
The default is a `CURRENT` versus `CURRENT` sanity check, not an improvement claim.

```powershell
.\gradlew.bat :more-battle-content-better-ai:evaluatePaired --no-daemon `
  -PevaluationPairs=10 -PevaluationSeed=20260906 -PevaluationTurns=30 `
  -PevaluationFormat=SINGLE -PevaluationTier=STANDARD `
  -PevaluationSplit=TUNING -PevaluationChallenger=CURRENT -PevaluationDefender=LEGACY
```

These are the defaults except defender (`CURRENT`). Both arms accept `CURRENT`
or `LEGACY`; formats and tiers match baseline capture. Pair count is 1–1,000.
`-PevaluationOutput=<new-directory>` selects an output directory; otherwise a
unique directory under `build/reports/betterai-paired/` is created.

The versioned corpus partitions **unordered pairs of complete-set teams** by a
stable hash (approximately 80% tuning, 20% holdout), with reserved fixed regression
pairs. Changing a seed, team order, or side cannot move the same pair to the other
partition. Individual species, moves and sets can occur in both partitions; this
does not test generalization to unseen species. Duplicate sampled pairs and fixed
regression pairs are excluded from the random sample.

Two fixed, seeded regression cases per format/partition check turn progression,
public move evidence and stalled battles. They are reported separately, **not**
added to the random-pair confidence interval. They are whole-battle regression
cases, not yet curated tests of counter preservation, sacrifice or doubles combos.

Held-out battle execution requires both `-PevaluationSplit=HOLDOUT` and
`-PallowHoldout`. Normal tests check partitioning without executing held-out
battles. Freeze the policy and sample count before using holdout results. If those
results inform tuning, retire that corpus version as held-out evidence; the flag
prevents accidental execution, not deliberate reuse or data access.

Each run writes `manifest.json`, `pairs.jsonl` (flushed only after both orientations
finish), and `summary.json` only after all requested pairs complete and input
fingerprints remain unchanged. The manifest includes full teams, both tunings,
partition rule and failure conditions. An existing directory is never overwritten.
Baseline `baselineReplay` does not accept paired manifests; retain the saved
options and fingerprints when reproducing a paired run.

The random summary keeps wins, losses and undecided games. A win scores 1, a loss
0 and an undecided game 0.5; the two scores are averaged into one pair score. The
reported mean is **not** a decisive-only win rate. A turn-limit cutoff also counts
as undecided, so short smoke runs must not be used to judge strength.

For `n` pair scores, the conservative 95% interval is the mean plus/minus
`sqrt(ln(40)/(2*n))`, clipped to `[0,1]`, using the
[two-sided Hoeffding bound](https://www.stat.cmu.edu/~cshalizi/sml/21/lectures/06/lecture-06.html).
Its population interpretation assumes independent representative pair outcomes
and a sample count fixed in advance; repeated peeking, selecting favorable seeds,
correlated cases and timed-search machine-load effects undermine that reading.
The tool does not automatically declare a stronger AI from this interval.

## Embedded Showdown oracle (initial scope)

This independent **scripted referee** executes the `showdown.zip` supplied by the
Cobblemon dependency on the test classpath. It does not use Better AI's projector,
download upstream Showdown, install npm packages, or start a server. Node.js must
be on `PATH` (verified with v24.14.1). Ordinary tests skip this external-runtime
check unless explicitly enabled:

```powershell
.\gradlew.bat :more-battle-content-better-ai:unitTest '-Ptests=EmbeddedShowdownOracleTest' -Poracle --no-daemon
.\gradlew.bat :more-battle-content-better-ai:captureOracle --no-daemon
```

`captureOracle` creates a unique directory under `build/reports/betterai-oracle/`.
Use `'-PoracleOutput=<new-directory>'` to select one. Existing runs are not
overwritten. Archive paths are constrained to the extraction directory, extraction
is bounded to 128 MiB of JS/JSON, and the child engine has a 30-second timeout.
The adapter supplies Cobblemon's mandatory `movesInfo` PP fields and stable UUIDs.

The diagnostic singles fixture uses deliberately unequal, small teams to exercise
voluntary switching, damage, fainting, forced replacement and victory. It is not a
legal competitive preset benchmark or a strength measurement. Engine ZIP and
adapter hashes, artifact version when available, Node version, explicit format,
PRNG seed, commands, teams and exact referee end state are recorded in `result.json`.
This file is written last as the completed-run marker. `engine/`, `oracle.cjs`,
`referee-output.json` and `stderr.txt` remain for diagnosis.

**Information boundary:** Cobblemon emits `pp_update` containing unrevealed move
names even in the spectator channel. The adapter therefore uses both the engine's
split-channel filtering and an explicit public-event allowlist; unknown/custom
events are discarded. `public-observation.json` contains only this filtered view.
All `referee*` fields/files and the combined `result.json` are privileged and must
never be passed as AI input. A regression changes unused opponent moves/items and
checks that the filtered observations stay identical while the referee sets differ.

Verified scope is only this scripted singles lifecycle with the embedded
`cobblemon` mod inheriting its base data. Runtime registry injection (species,
moves, abilities, items), Mega Showdown hooks, datapack overrides, bag items,
doubles and special mechanics are **not loaded/validated**. The format is recorded
and fixed for this fixture, not claimed to match a live facility's full rules.
This scripted fixture has no Better AI decision loop or full-turn projection
comparison. The separate base-damage comparison below covers only the damage
kernel. The log finding above is not evidence that the existing
production AI consumes `pp_update`.

## Base damage differential checks

```powershell
.\gradlew.bat :more-battle-content-better-ai:unitTest '-Ptests=EmbeddedDamageDifferentialTest' -Poracle --no-daemon
.\gradlew.bat :more-battle-content-better-ai:compareDamageOracle --no-daemon
```

The damage oracle calls the embedded engine's native `getDamage` with critical
hits disabled. It enumerates all 16 damage RNG values by controlling only the RNG
return, without replacing the engine's damage formula or randomizer. Eight
physical/special, STAB, neutral, resisted, super-effective and immune matchups at
six levels and three defender HP EV values give 144 cases. Fixtures deliberately
disable abilities/items and are not competitive team presets.

The test compares every damage roll against `ShowdownStandardDamageProjection`,
then checks conditional KO probability at every integer remaining HP. Exact
opponent stats are private synthetic referee inputs, not additions to the AI
observation contract. No production brain or live battle is used.

Reports go to a unique `build/reports/betterai-damage/` directory; override with
`'-PdamageOutput=<new-directory>'`. `comparison.json` records the native results,
engine/script and projection-class hashes, checks and mismatches. Mismatches are
saved **before** the command fails. `MATCH` applies only to this damage kernel
scope: accuracy, natural critical chance, abilities/items, status, field/weather,
spread, action order/cancellation, switching effects and end-of-turn effects are
not covered. This is not a complete turn-projector validation or strength verdict.

This comparison found an HP reconstruction defect: `122/362` multiplied back to
`122.00000000000001`, so rounding upward incorrectly used 123 HP. The correction
recognizes exact integer-derived ratios without applying an arbitrary epsilon;
genuinely higher fractions still round upward. It changes KO assessment, not the
damage-roll formula.
