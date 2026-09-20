# Workspace targets

- Client profile: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- Server root: `C:\Users\박주형\Documents\GitHub\Mincraft-Cobblemon-Server`
- Treat these as the authoritative client and server targets for inspection, compatibility work, and deployment unless the user explicitly names a different target.
- Keep source/build validation, client deployment, server deployment, and live gameplay verification as separate results.

## Version control

- Every source, configuration, documentation, build, or deployment change MUST be committed and pushed as its own logical unit.
- Do not combine unrelated work in one commit. Verify the exact staged file set before every commit and verify the remote is synchronized after every push.

## BattleCam port

- Port the Cobblemon 1.8.1 BattleCam replacement as `better-cobblemon-battlecam`.
- Use the Fabric mod ID `better_cobblemon_battlecam` so it does not collide with the external `battlecam` mod ID.
