# Managed PvE extension API — experimental v1

Status: implementation contract for MBC 1.6.22. Audience: server-side addon authors.

## Ownership

MBC owns battle construction, disposable party copies, trainer ownership, battle rules, AI, projections, cleanup and result delivery. The addon owns content definitions, eligibility, progression, persistent reward identities and screen state. The public API does not expose Tower sessions, selection types or progression.

- `api.battle.ManagedPveBattles`: capture a six-member party, start one battle, cancel the player's managed battle.
- `api.access.BattleContentAccess`: per-server scoped access providers with removable registration handles.
- `api.rewards.BattlePointRewards`: balance and idempotent content reward transactions.
- `api.presentation.TrainerResourceSkin`: local resource texture and default/slim selection.

Integration update (2026-09-26): preserve `Cobblemon173TowerPveBattleRuntime.startManaged(Cobblemon173ManagedAiBattle)`, used by the standalone Better AI test since c03987a5. The addon-side `ManagedPveBattleRuntime` now adapts to that existing lifecycle-owned engine, so the public API still exposes no Tower types. Preserve `unboundedBrainDecision` for the AI test and central temporary-entity lifecycle registration/cleanup. Factory's opponent-observation path remains specialized.

## Calling contract

Callers MUST invoke mutations on the owning server thread. Capture the party with `snapshotParty(player, cap)`; the result is opaque Cobblemon serialization, not a GUI payload. The API requires six distinct real party members, no existing battle and no member above the supplied cap. Each fight materializes fresh battle clones and fully heals them. It does not change the player's real party or award battle XP.

The addon MUST persist its run and `transactionId` before calling `start`. Requests specify namespaced content/trainer IDs, a translation key, opponent PokemonProperties strings, format, skill 0–5, optional selected mechanic and optional resource skin. Opponent species cannot be unknown/random; level and moves must be explicit. Form preservation uses MBC's existing catalog Pokemon creator. Bag items follow the existing managed-battle prohibition.

`start` returns a battle UUID or null. Null is not a victory. A completion callback receives WIN, LOSS or CANCELLED. The engine's completion attachment prevents duplicate battle notifications; if the addon callback throws, MBC retries settlement using its existing retry queue. The addon MUST make settlement idempotent: successful persistent writes can precede a later callback failure. The queue is in-memory, not a durable event journal across process termination. The addon therefore owns its restart policy.

## Facility access

Register providers on `SERVER_STARTING`, scoped to stable namespaced content IDs. No providers means allow. All applicable providers must allow; denial and provider exceptions fail closed for the declared content. Release the handle when stopping. Server-keyed state is also removed on `SERVER_STOPPED`.

OPEN/MUTATE/START checks cover Tower and Factory entrypoints and real battle launch. Finishing or abandoning an already running battle is not blocked. The optional `hub_access_v1` channel supplies denial translation keys and arguments to the hub; server checks remain authoritative even if the client has stale visual state.

## Reward and presentation constraints

BP retries MUST reuse transaction UUID, player UUID, amount, source ID and reason unchanged. A duplicate with changed values is a conflict, not another payment. The addon SHOULD keep receipts until its own persistence/reconciliation policy makes removal safe. This API does not promise filesystem-atomic commits spanning player data and multiple SavedData files.

Resource skins accept namespaced PNG paths, not URLs, absolute paths or traversal. Missing textures use a vanilla fallback. Resource-skin projections do not copy the player's equipment. The projection packet uses `shadow_trainer_show_v2`; older clients that cannot receive it skip this optional effect rather than decode a changed old packet layout.

## Verification boundary

Unit/build success is not an actual-world battle test. Before release, verify start/win/loss/disconnect, real-party non-mutation, both formats, supported mechanics, default/slim appearances, resource reload and absent-addon behavior in the target runtime.
