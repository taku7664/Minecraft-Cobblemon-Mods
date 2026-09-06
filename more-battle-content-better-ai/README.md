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

## Root allocation probe (early experiment, not enabled in the AI)

```powershell
.\gradlew.bat :more-battle-content-better-ai:unitTest -Ptests=Allocation --no-daemon
.\gradlew.bat :more-battle-content-better-ai:compareRootAllocation --no-daemon
```

This advances a small part of the planned search-policy experiment without
adopting a new product search. It compares round-robin sampling with a UCB-style
root allocator: inspect every action first, then favour promising or less-visited
actions. The reference is [poke-engine's MCTS implementation](https://github.com/pmariglia/poke-engine/blob/4909360fddc827942b18d840dd796385b46eb9e4/src/mcts.rs)
at commit `4909360fddc827942b18d840dd796385b46eb9e4` ([MIT license](https://github.com/pmariglia/poke-engine/blob/4909360fddc827942b18d840dd796385b46eb9e4/LICENSE)).
No upstream code or engine is bundled. This is an independently written root
bandit, not a tree search, a port of MCTS, or poke-engine's two-sided selection.

Two newly drawn complete team pairs (seed `20260906`) provide public contexts
from up to four turns each. Positions without a revealed damaging reply or public
active stats are excluded with reasons; first-turn switch-only responses must not
masquerade as a successful comparison. Flat expectations are flagged separately.
The existing public turn projector and leaf evaluator precompute a
one-turn outcome table. Opponent replies are **uniform over generated public
responses**, not the product opponent model or hidden sets. Order and chance
weights are normalized separately. Both allocators see the same sample prefix
for each action; only the allocation order changes. Every run uses the same
budget of 4, 16 or 64 draws per action in total, across 32 paired seeds. The UCB
exploration scale is fixed at one board unit, not a calibrated confidence bound.

The scorer, not the allocator, sees exact model expectations. `regret` is the
difference between the best expected board value and that of the selected action.
It is not win-rate loss. Precomputation, stored tables and class hashes are
recorded in `comparison.json` under `build/reports/betterai-allocation/`; use
`'-PallocationOutput=<new-directory>'` to choose a fresh output directory.

**Limits:** equal table draws do not mean equal engine calls, nodes, or server
time. This is neither a comparison against the shipping recursive search nor an
independent mechanics validation. No deeper continuation, adversarial opponent,
hidden-set hypotheses, doubles, trainer selection, or held-out strength test is
included. A regression deliberately demonstrates that both allocators can miss
a rare catastrophic loss. Better allocation alone does not make that safe.
Product selection, evaluation weights, Router ownership and defaults are unchanged.

## Live root cost comparison (test only)

```powershell
.\gradlew.bat :more-battle-content-better-ai:unitTest -Ptests=Root --no-daemon
.\gradlew.bat :more-battle-content-better-ai:compareLiveRootSearch --no-daemon
```

Unlike the precomputed probe, every attempted sample calls the existing **public
turn projector**, enumerates that pair's chance branches and evaluates only one
sampled leaf. No result-table cache is used. This is still a local projection,
not an independent Showdown execution or a live Minecraft battle.

Before adaptive allocation starts, every generated public action/reply pair is
checked once. The sampled score and the probability of a new ally faint across
all generated outcomes are kept separately. The maximum observed probability
across replies is a diagnostic, not a risk veto or a guarantee against hidden
responses, future turns, or outcomes omitted by the 64-branch limit. An incomplete
coverage pass yields **no recommendation**, not an apparently safe best action.
Interrupted or late samples are charged as attempted work but not accepted.

The runner uses the previous two team pairs and up to four turns, excludes
unsupported public contexts, and measures two separate comparisons:

- Uniform/UCB with 24 or 96 live draws and four paired seeds. Calls, generated
  branches and leaf evaluations are separate work counts; a call can cost more
  than another call.
- Uniform/UCB and existing Boss recursive **ranking** with 50/150ms active search
  windows, three repeats, per-context warm-up and rotated arm order. The probe
  reads the recursive evaluator's safety margin through test-only reflection and
  adds it to its configured budget (currently 70/170ms for a 20ms margin), so the
  active windows match. A renamed/missing constant fails the probe rather than
  silently invalidating this comparison. Both budgets are recorded. The recursive
  node ceiling is lifted for this experiment; chance width stays 64. Input
  calculation, base ranking and reply generation are shared setup outside the
  timers. Sampler setup is inside its timer. Actual elapsed time is reported:
  cancellation is cooperative, so the time limit is not a hard latency guarantee.

Recursive node counts are not sample counts. Recursive scoring also has deeper
turns, response aggregation and heuristic corrections that the one-turn sampler
does not reproduce. Thus this is a **cost/integration comparison**, not an
isolated strength comparison or permission to replace the product search. It
does not run the final personality-weighted product selector.

Reports are written to a fresh `build/reports/betterai-live-root/` directory;
override with `'-PliveRootOutput=<new-directory>'`. Production code, defaults,
Router ownership and deployment remain unchanged.

### Response-objective alignment (first slice)

`LocalSearchResponseObjective` now owns the existing recursive search's opponent
response aggregation: tier-weighted soft minimum, learned move/switch category
blend, execution probability and conservative remaining HP. The recursive search
uses this implementation; formulas, temperature and tier weights are unchanged.
It consumes local turn evaluations and public memory, not hidden sets or Router
recommendations. This extraction is not an AI-strength improvement.

The live allocation probe still uses raw leaf means. It is **not yet aligned**:
full-turn effects, unknown-response reserves, root heuristic corrections and
search depth must also match before an allocation-only quality comparison is
valid. Do not reinterpret the existing cost reports as matched-objective results.

Targeted regression: `./gradlew :more-battle-content-better-ai:unitTest -Ptests=LocalRecursive --no-daemon`.

### Common recursive referee (offline objective reference)

Run `./gradlew :more-battle-content-better-ai:compareRootObjective --no-daemon`.
Use `'-ProotObjectiveOutput=<fresh-directory>'` to override the output under
`build/reports/betterai-root-objective/`. Tests use `-Ptests=Root`.

The test-only reference calls the existing recursive evaluator for the complete
ranking, then for each root in reverse order with a fresh cache. It preserves the
complete public context and original base ranks. At Boss depth 1 and 2 it checks
ranking and score, lookahead adjustment, execution probability and worst-response
HP retention (absolute tolerance 1e-9). This reuses full-turn scoring, unknown
response reserves, habits and root corrections without duplicating their formulas.
Singles only: doubles root pruning needs its own equivalence proof.

The reference uses a constant offline clock with 200,000 nodes per depth and a
64-branch chance width. Incomplete depth or node exhaustion fails instead of
becoming an exact reference. Fresh per-root runs have different cache costs and
node-budget scope from the full run: this is **not equal-cost or latency evidence**.
It measures the current model, not independent mechanics or an optimal battle policy.

Live uniform/UCB choices (24/96 draws, four seeds, previous two team pairs) are
scored by that common referee without receiving its scores as inputs. `scoreLoss`
is the best recursive comparison score minus the chosen score, not win-rate loss
or the earlier board-value regret. Missing recommendations remain null, never
zero loss. Depth-1 and depth-2 rows reuse the same sampled choice and are correlated.

**The samplers still optimize raw leaf means.** A common referee makes the
disagreement measurable; it does not yet isolate allocation from objective
differences or justify adopting UCB. This task changes no product logic or defaults.

### Recursive-score root deepening (test-only scheduling)

`compareRootDeepening` connects production recursive scores to scheduling and
selection, not just post-hoc grading. All roots complete depth 1
first; depth 2 then follows either canonical action IDs or descending observed
depth-1 score. Each call performs real recursive work with the complete public
context. A complete depth-2 score replaces the observed depth-1 score; incomplete
results consume budget but never replace a completed observation. Default
`COMMON_DEPTH` selection retains the complete depth-1 snapshot until every root
completes depth 2, then accepts the entire depth-2 snapshot. Missing depth-1 coverage yields no
recommendation. All roots completed at depth 2 must match the full recursive
reference. No referee score is passed to either scheduler.

This is score-priority iterative deepening, **not UCB or MCTS**. Deterministic
full-turn scores are not new random samples when reevaluated at the same depth.
Both schedules use the same objective implementation, target depth, evaluator
and default acceptance rule. The runner crosses both schedules with both acceptance
modes (four arms). `LATEST_COMPLETED` explicitly opts into the legacy
mixed-depth selection for comparisons. It checks identical attempts, nodes and
observations across each acceptance pair, and validates accepted common-depth
scores against the matching reference depth. Common-depth acceptance avoids comparing
different horizons, but also defers useful partial warnings and delayed payoffs.
It does not guarantee monotonic quality against the depth-2 reference or real play.

The global budget counts reported recursive work checks, including interrupted
calls and repeated depth-1 work inside a depth-2 evaluation. The evaluator's
per-depth limit reserves its first denied check too. Nodes are not equal-cost
operations: base ranking and initial board preparation are outside that counter,
and caches are fresh per call. Elapsed time includes evaluator preparation, but
the offline clock does not enforce a latency deadline. Compare node budgets and
actual elapsed time separately. Neither scheduler is the production whole-tree
iterative-deepening baseline.

Run `./gradlew :more-battle-content-better-ai:compareRootDeepening --no-daemon`,
optionally with `'-ProotDeepeningOutput=<fresh-directory>'`. Reports under
`build/reports/betterai-root-deepening/` include per-root depths, observed scores,
attempts, accepted `selectionScores`/`selectionDepth`, charged/unused work and
depth-2 reference score loss. Observed `scores`/`depths` can still be mixed even
when the accepted snapshot is not. Tests use
`-Ptests=RootDeepening`. Singles/Boss depth 1–2 only; product defaults are unchanged.

The initial six-position measurement did not distinguish the schedules: depth 1
and 2 chose the same best action in every reference, and all recommendations had
zero target-score loss. This validates integration, not a quality gain. Before
adoption, add public tactical fixtures with depth-dependent best actions and
measure failure rates as well as cost; do not select fixtures solely because the
priority schedule wins on them.

### Fixed tactical fixtures

Run `./gradlew :more-battle-content-better-ai:compareRootTactics --no-daemon`
with optional `'-ProotTacticsOutput=<fresh-directory>'`. Results go under
`build/reports/betterai-root-tactics/`; tests use `-Ptests=RootTactical`.

The fixture set includes all 18 combinations of own HP (0.4/1.0), physical strike
power (60/90/120) and revealed opponent strike power (30/60/90), with Swords Dance
as the alternative. Two controls cover an immediate finishing hit and a priority
finisher that prevents a lethal faster reply. The latter's projected survival
and knockout are checked separately from AI ranking. All cells are retained;
the fixture builder never queries a scheduler or filters by its success.

These are synthetic 1v1 public snapshots: 200 HP, specified point stat ranges,
normal typing, complete revealed move catalogs and custom strike templates.
They are not legal preset teams, hidden-set inference, independent-engine ground
truth, holdout battles, or proof of in-game quality. The two-turn ranking is the
current model's reference, not an independently established optimal move.

The same scheduler runner measures 25/50/100/200/400/800/1600 reported-work
budgets, records depth-dependent best-action changes, and fails if the tactical
grid no longer contains any such change. Canonical ordering depends on action
IDs (here `setup` precedes `strike`); comparisons against it are not a claim of
general superiority over other move orderings or the production search.

The initial tactical run exposes non-monotonic choice quality under mixed depths:
at 100 work checks canonical ordering misses the reference best in 7/20 cases,
score priority in 9/20, while both match all 20 after full depth completion at 200.
In `setup_hp40_hit120_reply60`, score priority chooses the reference best at 50,
switches to a worse setup at 100, and returns to the best at 200. The legacy rule
is retained as an explicit comparison mode. Default common-depth acceptance now
keeps the finishing strike at all three budgets in this regression. The current
production evaluator does not use either test-only scheduler; adoption remains
unapproved.

The acceptance comparison (20 fixtures, 560 rows) reproduces every legacy choice,
score and work count. At budget 100, common-depth selection reduces reference
misses to 4/20 for either schedule, with mean score loss 0.408 (legacy canonical:
3.408375; legacy score-priority: 16.52625). Both schedules reach zero loss at 200.
All acceptance pairs perform identical work, with no budget overruns or reference
score mismatches. The remaining four misses require the deeper setup payoff;
common-depth acceptance deliberately waits for full coverage. This fixture set
therefore demonstrates a comparison fix, not a remaining advantage of priority
scheduling. Unit tests also preserve a counterexample where legacy acceptance
uses a helpful early warning that common-depth selection defers. The six recorded
positions are retained as a separate 168-row integration regression, not tactical
quality evidence. None of these model-score losses is a win-rate measurement.

### Native turn order and lethal cancellation differential

Run `./gradlew :more-battle-content-better-ai:compareTurnOrder --no-daemon`,
optionally with `'-PturnOrderOutput=<fresh-directory>'`. The report under
`build/reports/betterai-turn-order/` includes native cases, mismatch categories,
engine/adapter hashes and source provenance. Tests use `unitTest -Ptests=Embedded -Poracle`.

Eight scripted native turns cover faster/slower attacks, move priority, Trick Room
with and without priority, speed stages +1/-1, and Tailwind overcoming a speed drop.
Both combatants start at 1 HP, so damage rolls and critical hits cannot change which
connecting attack is lethal. The native engine's first actor, sole executed move,
and fainted side are checked against predeclared expectations, then against both
the public order calculator and all outcomes of the complete-turn projector.

Exact native stats are confined to synthetic test inputs; this is not a live
opponent-stat adapter. No product AI code changes. Speed ties, paralysis, damage
distributions, end-turn effects, doubles, addon registrations and actual battle
quality are outside this slice. Passing these cases does not complete mechanics
validation or establish stronger AI play.

### Poison, burn and toxic residual regression

`unitTest -Ptests=EmbeddedStatusResidual -Poracle` runs 24 native end-turn cases:
Snorlax (235 HP) and level-1 Pikachu (12 HP), full or 1 remaining HP, with poison,
burn, and toxic turns 1/2/3/15. It compares remaining HP, fainting and living counts.
Regular poison now uses 1/8, not burn's 1/16. With an exact public max-HP range,
status damage rounds down to integer HP with a minimum of 1; toxic multiplies
that rounded base tick. Exactly representable integer HP and damage are subtracted
as integers before converting back, preventing floating-point ghost survival after
repeated ticks. Fractional expectations are not snapped to integer HP.
Missing or ranged max HP keeps the fractional estimate,
without selecting a hidden exact stat. These cases do not validate healing/residual
ordering, weather, Salt Cure, special forms, or actual battle quality.

### Ordered passive recovery and residual damage

`unitTest -Ptests=EmbeddedPassiveResidual -Poracle` compares 54 native turns:
nine Leftovers/Poison Heal/Magic Guard/status/Salt Cure combinations at levels 1
and 50, each at full, half and 1 HP. Synthetic species/ability combinations isolate
mechanics; they are not legal team presets. The implemented order is Leftovers
healing, poison/burn (including Poison Heal), then Salt Cure. Each event clamps HP
separately and no later event revives a fainted Pokemon. Leftovers and Poison Heal
both apply; Poison Heal does not suppress Salt Cure, whereas Magic Guard does.
Known exact HP also uses native integer ticks for healing and Salt Cure. This
extends the earlier status-only slice; weather, other passive effects, special
forms and live battle quality remain unverified.

### Sandstorm residual and weather expiry

`unitTest -Ptests=EmbeddedWeatherResidual -Poracle` compares 120 native turns:
15 profiles at levels 1/50, full/1 HP and one/two remaining weather turns.
Sandstorm now damages before passive healing, except for publicly known Rock,
Ground or Steel types, Magic Guard, Overcoat, Sand Veil, Sand Rush, Sand Force,
Safety Goggles, or an active living Air Lock/Cloud Nine user. Expiring weather
ends before damage; suppression does not stop its duration decreasing. Snow is
a no-damage control. The low-HP cases prevent healing from reviving a Pokemon
already knocked out by weather. Public exact max HP uses integer ticks.

These synthetic fixtures are not legal teams or AI-quality evidence. Opponent
exact combat stats are not passed to the public state. Unknown max HP retains
fractional damage, and unknown/ranged weather duration retains the existing
continued-weather estimate until its maximum expires, not an expiry probability
distribution. Hidden immunities are not read. Hail, weather-triggered abilities,
ability/item suppression, semi-invulnerable states, doubles and live runtime
registrations remain outside this slice.

### Board material ownership

`LocalBoardMaterial` owns HP, living-Pokemon value, unseen-living estimates and
the corresponding probabilistic removal credit. Both immediate turn deltas and
leaf evaluation use it. `LocalImmediateTurnScorer` still owns stage/status/field
changes; the leaf evaluator still owns projected attack pressure and initiative.
Tactical ranking's KO bonus remains a separate policy term. This refactor does
not alter weights, discounting, pruning, or the immediate/future KO combination.

`unitTest -Ptests=LocalMaterialOwnership` characterizes nine material boards and
both-sided removal credit at three HP levels and five clamped probabilities.
The existing 20-position tactical probe produced identical reference values and
all 560 comparison rows before/after, excluding elapsed time. That is scoped
behavior-preservation evidence, not a win-rate or real-time performance claim.
The fixed value of surviving Pokemon and full-HP unseen estimates remain model
assumptions to evaluate in the later team-role work.

### Immediate KO correction does not use future gain

The root KO duplicate-credit correction is capped by the completed one-turn
gain, not the gain at the current search depth. Later gains/losses therefore do
not change this immediate correction when foresight weight is zero. Legacy
correction, search objectives, budgets and tuning defaults are unchanged.

`unitTest -Ptests=LocalForesightOwnership` compares 20 public tactical fixtures
at 40 opponent-HP levels, recalculating candidate facts after every HP change.
With equal budgets, fixed clocks, completed depths and equal response coverage,
1,640 action comparisons had seven mismatches before the fix and none after it.
A separate check keeps nonzero foresight effective. These are synthetic model
regressions, not legal-team or win-rate evidence. This KO-only correction did
not separate depth-dependent response coverage; the follow-up below does.

### Current-turn and future response coverage

The completed one-turn coverage now weights the immediate score (including its
KO correction), while the current depth's coverage weights only the foresight
term. Both terms are added before the existing score clamp. A later unknown
replacement therefore cannot reduce a fully modelled current-turn score when
foresight is disabled. No hidden replacement is invented or read.

`LocalForesightOwnershipTest` includes a known finishing turn followed by an
unseen opposing replacement, and reverses the candidate traversal order. The
zero-foresight scores agree at completed depths 1/2. The existing 1,640 comparisons
and nonzero-foresight control remain. `responseCoverageByAction` reports immediate
and future coverage for accepted results; the older aggregate field describes
the last probed action, not a universal score multiplier. These checks do not
prove quality with unknown teams, deadline-limited choices or all double-battle
branches. Search authority and coverage priors remain unchanged.

### Local Brain to native battle smoke loop

`unitTest -Ptests=EmbeddedAiDecisionLoop -Poracle` calls the real
`LocalTacticalBrain` with two eligible moves, submits its chosen command to the
embedded engine, and reads the next public board until victory. The opposing
policy remains scripted Splash plus forced replacement. The referee never
chooses or substitutes the Local Brain's action. Each step replays the submitted
history with the same engine seed, with a 12-decision safety bound.

The fixture is level-50 Pikachu against two level-5 Magikarp, not a competitive
team evaluation. Opponent identity/HP comes from allowed spectator events;
unrevealed moves, ability, item and stats are not decision inputs. A hidden unused
move/item/attack-IV/EV/nature variant produces identical decision inputs and chosen actions. Own PP
comes from the own-side request, not Cobblemon's publicly emitted `pp_update`.

The adapter now supplies exact own stats from the own-side request, opponent
stat ranges from publicly revealed species base stats through the core's shared
`BattlePublicStatRanges`, and an own eligible-move catalog. Opponent ranges are
independent per-stat bounds, not an inferred exact build. The own catalog is
conservatively incomplete because disabled/exhausted moves are filtered; the
opponent move catalog remains unknown. A test-only observer delegates unchanged
to the shipping weighted selector and checks that every decision contains a
nonzero recursive-search adjustment (the pre-change fixture recorded zero).
This proves a search contribution reaches selection, not that it changes the
chosen move or makes the Brain stronger. The fixture still lacks a full public
event/memory adapter and does not validate deep tactical search, full legal
presets, either-side forced-choice handling, doubles,
runtime addons, win rates or gameplay. Replaying from scratch is a bounded smoke
mechanism, not a performance measurement or the final long-battle transport.
JSON is Base64/UTF-8 encoded for Windows-safe argument transport, not secrecy.

### Full raw Factory preset audit

Run `:more-battle-content-better-ai:auditPresetOracle` (Node.js required), with
optional `-PpresetAuditOutput=<new-directory>`. This bypasses the simulation
roster's move-metadata filter and accounts for every original rental set by ID.
The report retains rejected sets; it never repairs the catalog or silently
removes a set. Large inputs are passed through a create-new JSON file, not a
command-line argument. `preset-input.json` preserves the raw inputs, and
`audit.json` records catalog/engine/adapter hashes and the engine version.
Audit defaults are explicit: level 50, absent IVs 31, fixed species gender where
applicable and male otherwise. The catalog has no gender field; the validator
otherwise fills it randomly outside the battle seed. This deterministic audit
assignment is not a statement about the actual runtime Pokemon's gender and
does not evaluate alternative-gender event legality.

Two checks are separate: `registryCompatible` requires known species/form,
ability, item, nature, four distinct known moves and valid stat spreads;
`obtainable` uses the embedded `TeamValidator` with `Obtainable` and
`Max Level = 50`. `obtainableChecked=false` means the registry/shape gate prevented
that second check. The validator receives a copy, with `engineSet` and its
possibly normalized `validatedSet` preserved separately. Unknown forms are
rejected, never replaced with their base species.

Every registry-compatible set also starts in a fresh, fixed-seed native singles
battle against a Splash Magikarp under empty battle rules. This independently
records actual starting species, effective types, ability, item and max HP;
initialization errors and static-species/type mismatches are explicit counters.
These are privileged referee diagnostics, never public decision inputs. For
example, Multitype can leave an Arceus-Bug label while making its effective type
Normal without the required plate. Validation warnings alone do not prove that
a form actually reverts. No turns or AI decisions are executed by this audit.

This is individual-set compatibility with the embedded base-gen9 rules, not
proof of our game's legality or full-team legality. Level-50 facility scaling
can conflict with source-game event/learnset minimum levels; required form items
and Pokemon GO origin constraints can also differ from runtime integrations.
Do not delete or rewrite presets merely because this report rejects them.
The audit does not execute AI battles, sample teams, load runtime addon
registrations, or prove battle quality. Run
`unitTest -Ptests=EmbeddedPresetAudit -Poracle` for unknown-move,
impossible-ability and actual Arceus starting-type controls.

### Public temporary type observation

The core observation adapter now consumes explicit `-start ... typechange` and
`typeadd` messages for both sides. Replacement clears the added type; a new
added type replaces the previous addition. `-end ... typeadd` removes it.
Ordinary HP/status snapshots preserve the public override, while switching out,
re-entry and battle reset clear it. The common state assembler also applies the
override to own active Pokemon, so both Brain owners receive the same facts.

Missing or unsupported type values become UNKNOWN (an empty known-type set),
not the old species types or a hidden target lookup. Reflect Type is one native
example whose first event omits the value; a later explicit public type update
can resolve it. This does not resolve unannounced Multitype
types, Terastallization, Transform, Illusion or all form-change messages.

`unitTest -Ptests=EmbeddedTypeChanges -Poracle` independently executes three
native protocol fixtures with ten checkpoints: Soak plus switch reset,
Forest's Curse/Trick-or-Treat/Soak, and Reflect Type's missing-value event followed
by the explicit silent type update. Core unit tests
exercise the parser, knowledge lifetime and own-state assembly separately;
this is not an end-to-end live Cobblemon adapter or full-preset AI battle test.

### Fresh complete preset team sampling

`auditPresetOracle -PpresetTeamPairs=100 -PpresetTeamSeed=20260906` additionally
draws 100 fresh 3-vs-3 team pairs from individually Obtainable, successfully
initialized presets. The evaluation policy adds Species Clause (including forms
of the same base species) and Item Clause. This is not a change to facility rules.
Each draw samples sets without replacement within a team; compatible sets are
accepted in draw order. It is not uniform sampling over all legal team combinations.
Teams may recur across battles; no fixed toy roster or global exhaustion is used.

Every whole team is checked by the native validator and both sides are initialized
together. The report retains original complete engine sets, separately normalized
validator copies, set IDs, sampling seed/algorithm, battle seeds and eligible count.
Source presets are never repaired. Insufficient compatible data or any team
validation/initialization failure stops the run rather than silently substituting
an easier team. The default pair count remains zero; the limit is 1,000 pairs.

The fixed 100-pair regression checks 200 distinct teams, exact replay and a changed
seed, plus insufficient-pool failure. This prepares reproducible teams; it does
not execute battle turns, connect the full AI input adapter, demonstrate win rate,
or validate runtime addons. Team sets and initialization data remain referee-only.
