# Pixel League dashboard in the development world, 2026-09-27

Updates: [Pixel League production home](PIXEL_LEAGUE_HOME_2026-09-27.md). The prior capture remains historical; this document records the subsequent dashboard redesign against the user's large-title, player-head, rank-ball reference.

- [Actual terminal home](league-dashboard-2026-09-27-top.png)
- [Actual home after selecting Gardenia](league-dashboard-2026-09-27-selected.png)

The top-left title is rendered as large `PIXEL LEAGUE` text. The current player's skin head is immediately left of the rank's Cobblemon Ball item; rank, badge count, level cap, and BP come from the server snapshot. The eight route icons are the Pokebadges item IDs from the loaded challenge definitions, rather than UI-hardcoded Sinnoh artwork. Gym selection, keyboard focus, Refresh acknowledgement, and Close were exercised through the real terminal in a copied `league-wiring-smoke` development world. The progression revision remained unchanged.

The selected card displays only known challenge status and cap reward. The reference's party, TM, and extra BP reward rows were not reproduced because the current state packet does not supply those facts. The copied development world's Cobbled Level Control settings report `spawn_scaling_conflict`, so the visible warning and disabled Challenge button are real server feedback. This run does not establish a successful gym battle or gameplay in the authoritative Modrinth profile. The profile JAR, server, CLC configuration, and module version were not changed.

Verification: League `unitTest` passed 61 tests; final `runClient` completed successfully with the terminal interaction smoke pass. English and Korean League language files each contain the same 123 keys. The first-join tutorial toasts were dismissed by the opt-in capture harness before screenshots; the production screen code does not suppress them.
