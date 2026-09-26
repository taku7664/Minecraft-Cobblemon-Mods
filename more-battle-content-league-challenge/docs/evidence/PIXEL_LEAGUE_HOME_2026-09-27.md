# Pixel League production home, 2026-09-27

This is the production terminal screen, not the UI Kit gallery or the older League fixture. The visual target was the two final Pixel League gallery screenshots supplied by the user. The actual home uses the shared pixel-framed shell, dark title strip, blue body, cream/yellow compact challenge buttons, badge step track, light selected-challenge panel, status callout, and distinct footer actions.

- [Home, top](league-live-1790441386332-top.png)
- [Home, challenge choices after End-key scroll](league-live-1790441386332-scrolled.png)

The gallery reference demonstrates widgets; it does not contain real League progression. The production screen intentionally uses server-reported rank, badges, cap, BP, challenge status and active run. No trainer portrait is shown because the current League state packet has no trainer appearance field. Elite Four and Champion remain one server-owned continuous-run entry.

The opt-in smoke harness entered the copied `league-wiring-smoke` world, placed and used a terminal, received the server snapshot, scrolled with End, moved keyboard focus, clicked Refresh, received the server acknowledgement, closed the screen, and confirmed no progression revision change or background reopen. Existing screenshot files were not overwritten. The two images above were compared to the final Pixel League screenshots: the outer frame and buttons now use the pixel styling, and challenge buttons size to their labels rather than stretching across equal-width cards.

The copied world's Cobbled Level Control configuration still reports `spawn_scaling_conflict`, so the status warning and disabled Challenge action in these images are real server feedback, not a simulated success state. Introductory Minecraft toasts cover part of the upper-right corner. This smoke run does **not** establish a successful gym fight, a configured trainer skin, physical mouse/controller input, Korean in-game rendering, or gameplay on the authoritative `cobblemon-dev` profile.
