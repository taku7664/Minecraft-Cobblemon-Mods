# Cobblemon Party Sidebar

Shows your Cobblemon party in a column docked to the survival inventory and the Pokemon fusion screen. Each row is a party slot: the Pokemon in a
frame like the player-model box, then the item it holds in an inventory slot. The column is cut from the active
inventory texture, so it matches the inventory under any resource pack.

- **Held items:** the held-item slot works like an inventory slot. Click to swap it with the item on your cursor,
  shift-click to send it to the inventory, or drag an item between the slot and the inventory.
- **Pokemon slots:** clicking a model immediately converts that Pokemon into an item on the cursor.
  Click an empty Pokemon slot to restore it. Click an occupied Pokemon slot to place the cursor Pokemon there
  and pick up the previous Pokemon as an item. Inventory slots work like normal Minecraft item slots.
- **Tooltip:** hover a Pokemon to see its level, name and gender, HP, nature and ability, moves with their PP, and the
  IVs and EVs of each stat, its capture-ball icon and native XS/S/M/L/XL size category. Shiny names and their `★` marker are yellow. No action hint is appended. The shared tooltip belongs to Cobblemon To Item. Stats its nature raises are red, lowered ones blue.
- **Quick move:** shift-click a Pokemon to itemize it directly into the inventory or hotbar.
  If both are full, the client refuses the request and shows the inventory-full message; the server also checks
  capacity before removing the Pokemon from the party and never drops sidebar quick-move output.
  Ordinary pickup only requires an empty cursor.
  Shift-click a Pokemon item in the survival inventory or hotbar to restore it to the lowest empty party slot.
  A full party refuses quick restoration; right-clicking a held item can overflow to the PC instead.
  Inventory items in chest and fusion screens keep their normal shift-click behavior. No itemize key or separate model drag is registered.
- **Buttons:** four small buttons sit under the rows. The first, holding Mega Showdown's DNA Splicers, opens
  PokeFusion's window as `/pokefusion` does (greyed out when the server has no such command for you; without Mega
  Showdown it shows a letter). Then **add preset**, **delete preset** (click twice within three seconds) and the
  **item storage**. Hover any of them for its name.
- **Presets:** `< 1 / 2 >` under the buttons switches presets. The current preset always follows the party: which
  Pokemon stands in each row and which storage item it borrows. Switching sends the Pokemon the other preset does not
  use to the PC, brings its own from the PC, returns every borrowed item to the storage and lends that preset's items
  out again. A new preset starts as a copy of the current one; deleting one switches to its neighbour first. Up to 18
  presets; switching, adding and deleting are refused during a battle, while the cursor holds an item, or when the PC
  has no room for the Pokemon leaving the party.
- **Item storage:** a 27-slot chest that belongs to you, opened from its button; Esc goes back to the inventory and
  the column stays beside it. Items in it, and items Pokemon borrow from it, wear a small chest in the corner and
  before their name. Pick one up in the storage and click a held-item slot to lend it: the Pokemon really holds it, so
  battles and evolutions see it. Taking a borrowed item off a Pokemon puts it back in the storage, not the inventory.
  An item placed in the inventory is taken out of the storage and loses the mark.
- **Missing items:** a held-item slot turns red when its preset lends an item the Pokemon is not holding (a battle used
  it up, or the storage had none left when switching); hover it to see which.

Held items cannot be changed during a battle, and Cobblemon's held-item tags (`cobblemon:held/blacklisted_items_to_hold`,
`cobblemon:held/whitelisted_items_to_hold`) apply. The column is not shown in the creative inventory. With EMI
installed, EMI keeps its side panels clear of the column.

## Installation

Install the same version on **both client and server**. Requires Minecraft 1.21.1, Java 21, Fabric Loader 0.19.5+,
Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20+ and Cobblemon 1.8.1 (below 1.9.0). Mod Menu and EMI
are optional; Cobblemon To Item 0.1.2+ is required. Cobblemon has no packet for a client to change a held item, so the server half makes that change; on a
server without this mod the column only shows the party; slot interactions need the server half.

## Settings

Mod Menu opens the client settings: **Party column** (show the column) and **Pokémon tooltip**, both On by default.
Done saves `config/cobblemon_party_sidebar.json`.

The server reads `config/cobblemon_party_sidebar_server.json` at startup. `consumeStoredItemsInBattle` (default `true`)
lets a battle use up or lose a borrowed storage item like any held item; `false` leaves borrowed items on the Pokemon.
Presets and storage contents are saved per player in the world's `data/cobblemon_party_sidebar.dat`.

## License

[MIT](LICENSE). Build from `works`: `./gradlew :cobblemon-party-sidebar:build`.
