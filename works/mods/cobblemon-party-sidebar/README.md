# Cobblemon Party Sidebar

Shows your Cobblemon party in a column docked to the survival inventory. Each row is a party slot: the Pokemon in a
frame like the player-model box, then the item it holds in an inventory slot. The column is cut from the active
inventory texture, so it matches the inventory under any resource pack.

- **Held items:** the held-item slot works like an inventory slot. Click to swap it with the item on your cursor,
  shift-click to send it to the inventory, or drag an item between the slot and the inventory.
- **Party order:** drag a Pokemon onto another row to swap the two, or onto an empty row to move it there.
- **Tooltip:** hover a Pokemon to see its level, name and gender, HP, nature and ability, moves with their PP, and the
  IVs and EVs of each stat. Stats its nature raises are red, lowered ones blue.
- **Itemize:** with PokemonToItem on the server, drag a Pokemon onto the inventory (or onto an empty slot there), or
  press **I** (rebindable) over it, to turn it into a PokemonToItem item, as `/poketoitem` would. Drop or click such an
  item on an empty Pokemon frame to put the Pokemon back in that party slot, as `/itemtopoke` would. Each needs
  permission for the matching command; the Pokemon tooltip names the key.

Held items cannot be changed during a battle, and Cobblemon's held-item tags (`cobblemon:held/blacklisted_items_to_hold`,
`cobblemon:held/whitelisted_items_to_hold`) apply. The column is not shown in the creative inventory. With EMI
installed, EMI keeps its side panels clear of the column.

## Installation

Install the same version on **both client and server**. Requires Minecraft 1.21.1, Java 21, Fabric Loader 0.19.5+,
Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20+ and Cobblemon 1.8.1 (below 1.9.0). Mod Menu, EMI and
PokemonToItem are optional. Cobblemon has no packet for a client to change a held item, so the server half makes that change; on a
server without this mod the column only shows the party and reordering still works.

## Settings

Mod Menu opens the client settings: **Party column** (show the column) and **Pokémon tooltip**, both On by default.
Done saves `config/cobblemon_party_sidebar.json`.

## License

[MIT](LICENSE). Build from `works`: `./gradlew :cobblemon-party-sidebar:build`.
