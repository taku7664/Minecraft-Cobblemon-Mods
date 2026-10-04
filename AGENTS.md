# Workspace targets

- Development server: `develop-product/server` in this repository (git-ignored). It replaces the old `dev-server/`
  folder; there is no other server root. Do not refer to or deploy into any server outside this repository.
- Development client: `develop-product/client` in this repository (git-ignored), the former `cobblemon-dev` Modrinth
  profile. `%APPDATA%\ModrinthApp\profiles\cobblemon-dev` is only a junction to it so the Modrinth App still launches
  it; refer to the repository path, never the AppData one. Do not copy JARs into it while its game is running.
- Keep source/build validation, client deployment, server deployment, and live gameplay verification as separate results.

## Product folders

- `deploy-product/client` and `deploy-product/server` hold the release outputs: the finished mod JARs a player's client
  or the live server needs, and each folder's `VERSION.txt`. A mod that runs on both sides goes in both folders.
- Nothing else goes in `deploy-product`: no sources/dev JARs, backups, logs, reports, scratch files or notes.
  Replacing a JAR removes the old one; a previous build is recovered by rebuilding its commit.
- `VERSION.txt` records, per version, what changed in that folder's set of mods. Releases are published on Modrinth;
  the record is how the changes are noticed and shared. Versions are `v0.x` until the first live release, `v1.0`.
- `develop-product/server` and `develop-product/client` are the running development server and client. Deploys there
  install JARs only; do not leave backups, temporary copies or working files in them.
- Intermediate and temporary files (downloads, decompiled sources, captures, logs, probes) belong in `build/` or a
  session scratch directory, never in either product folder or the repository root.

## Version control

- Every source, configuration, documentation, build, or deployment change MUST be committed and pushed as its own logical unit.
- Do not combine unrelated work in one commit. Verify the exact staged file set before every commit and verify the remote is synchronized after every push.

## Mod implementation

- Every mod source and resource change MUST account for localization and MUST follow a consistent project structure across modules.
- When a feature requires or reasonably benefits from user configuration or editing, the mod MUST provide a `modmenu` entrypoint in `fabric.mod.json` and MUST implement the corresponding configuration screen.

## BattleCam port

- Port the Cobblemon 1.8.1 BattleCam replacement as `better-cobblemon-battlecam`.
- Use the Fabric mod ID `better_cobblemon_battlecam` so it does not collide with the external `battlecam` mod ID.
