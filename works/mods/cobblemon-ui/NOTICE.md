# Licensing and attribution

## Project files

Cobblemon UI uses the MIT License, matching the repository license and `fabric.mod.json`. `LICENSE` contains the project notice: Copyright (c) 2026 jbro. The JAR includes it as `LICENSE_cobblemon_ui`.

MIT permits use, modification, redistribution, sublicensing, and commercial use, provided the applicable copyright and permission notices are preserved. It includes a warranty/liability disclaimer. It does not grant ownership of third-party trademarks or replace third-party licenses.

## Derived battle UI

Early development used **Cobblemon Extended Battle UI**, by **sveniik**, as a starting point. The current user interface has been independently redesigned. This attribution concerns retained code lineage rather than the current visual design. Direct comparison on 2026-10-07 confirmed retained implementation in `battle/state/StatTracker.kt` (including the map, stage update, and clearing methods):

- Source: https://github.com/sveniik/CobblemonExtendedBattleUI
- Original license: https://github.com/sveniik/CobblemonExtendedBattleUI/blob/main/LICENSE
- Preserved local notice: `THIRD_PARTY_LICENSE_CobblemonExtendedBattleUI`

The upstream notice says `Copyright (c) 2025` without a named holder. It is retained verbatim; the project author credit does not replace it. Both notices and this document are included in the JAR.

## Assets and dependency boundary

The distribution icon was newly generated for this module using OpenAI's built-in image generation tool. It is supplied with the project files under MIT to the extent the distributor holds applicable rights; that label is not a guarantee that generated artwork attracts copyright protection.

`textures/gui/popup_frame.png` is byte-identical to the upstream texture checked on 2026-10-07 (SHA-256 `2a75565d625c406d8248843fc4280d0dc19df46a043e98d2f3286e3e982e2785`); it is covered by the preserved upstream MIT notice.

The pixel textures (`pixel/info.png` and `pixel/selector.png`) were introduced in repository commit `537d527a39a0c096175290bb4561d629aa0ddabb` on 2026-09-26. The commit establishes when they entered this project but does not independently establish their original authorship. Before public release, the maintainer should verify their provenance or replace them with assets whose distribution rights are documented. No new claim of ownership over those two textures is made here.

Minecraft, Cobblemon, Fabric, Kotlin, Mod Menu, and Cloth Config retain their respective licenses and rights. Referencing their runtime assets does not relicense them under this module's MIT notice. The ordinary standalone module JAR does not intentionally bundle their code or assets; inspect the final artifact whenever packaging changes.

## Review scope — 2026-10-07

Checked the repository's MIT text, the upstream MIT notice, the module metadata, and the JAR packaging rules. This records the project's technical licensing state; existing texture provenance remains unresolved as described above.
