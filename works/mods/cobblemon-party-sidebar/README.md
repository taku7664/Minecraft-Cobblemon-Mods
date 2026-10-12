# Cobblemon Party Sidebar

Shows your Cobblemon party in a column docked to the survival inventory. Each row is a party slot: the Pokemon in a
frame like the player-model box, then the item it holds in an inventory slot. The column is cut from the active
inventory texture, so it matches the inventory under any resource pack.

- **Held items:** the held-item slot works like an inventory slot. Click to swap it with the item on your cursor,
  shift-click to send it to the inventory, or drag an item between the slot and the inventory.
- **Pokemon slots:** clicking a model immediately converts that Pokemon into an item on the cursor.
  Click an empty Pokemon slot to restore it. Click an occupied Pokemon slot to place the cursor Pokemon there
  and pick up the previous Pokemon as an item. Inventory slots work like normal Minecraft item slots.
- **Tooltip:** hover a Pokemon to see its level, name and gender, HP, nature and ability, moves with their PP, and the
  IVs and EVs of each stat, its capture-ball icon and native XS/S/M/L/XL size category. The shared tooltip belongs to Cobblemon To Item. Stats its nature raises are red, lowered ones blue.
- **Quick move:** shift-click a Pokemon to itemize it directly into the inventory or hotbar.
  If both are full, the Pokemon stays in the party. Ordinary pickup only requires an empty cursor.
  Shift-click a Pokemon item in the survival inventory or hotbar to restore it to the lowest empty party slot.
  A full party refuses quick restoration; right-clicking a held item can overflow to the PC instead.
  Chest and fusion screens keep their normal shift-click behavior. No itemize key or separate model drag is registered.
- **Fusion:** the slot at the foot of the column, holding Mega Showdown's DNA Splicers, opens PokeFusion's window, as
  `/pokefusion` does; hover it for its name. Without Mega Showdown it is a labelled button. It is greyed out when the
  server has no such command for you.

Held items cannot be changed during a battle, and Cobblemon's held-item tags (`cobblemon:held/blacklisted_items_to_hold`,
`cobblemon:held/whitelisted_items_to_hold`) apply. The column is not shown in the creative inventory. With EMI
installed, EMI keeps its side panels clear of the column.

## Installation

Install the same version on **both client and server**. Requires Minecraft 1.21.1, Java 21, Fabric Loader 0.19.5+,
Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20+ and Cobblemon 1.8.1 (below 1.9.0). Mod Menu and EMI
are optional; Cobblemon To Item 0.1.0+ is required. Cobblemon has no packet for a client to change a held item, so the server half makes that change; on a
server without this mod the column only shows the party; slot interactions need the server half.

## Settings

Mod Menu opens the client settings: **Party column** (show the column) and **Pokémon tooltip**, both On by default.
Done saves `config/cobblemon_party_sidebar.json`.

## License

[MIT](LICENSE). Build from `works`: `./gradlew :cobblemon-party-sidebar:build`.
