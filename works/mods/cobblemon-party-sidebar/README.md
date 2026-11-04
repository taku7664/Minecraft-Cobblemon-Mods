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
  Beside the Pokémon storage or the fusion window, shift-clicking a Pokémon sends it into that window instead (the
  storage's first free slot; fusion's base slot, then the first empty material slot), and beside the item storage
  shift-clicking a held item puts it in the storage. When the window is full it goes to the inventory as usual.
  Shift-clicking a Pokémon in the Pokémon storage puts it in the lowest empty party slot; a full party leaves it there.
- **Top row:** the current preset's name (click it in the inventory to rename, up to 16 characters; blank shows
  "Preset N"), then **add preset** and **delete preset** (click twice within three seconds).
- **Bottom row:** **fusion** (Mega Showdown's DNA Splicers; opens PokeFusion's window as `/pokefusion` does, greyed
  out when the server has no such command for you), the **item storage** and the **Pokémon storage**. Pressing the
  open window's button again, or Esc, goes back to the inventory; this also holds for the fusion window. Below them
  `< 1 / 2 >` switches presets. Hover any button for its name.
- **Presets:** the current preset always follows the party: which Pokémon stands in each row and which storage item
  it borrows. Switching puts the Pokémon the other preset does not use into the Pokémon storage as items, takes that
  preset's Pokémon out of the Pokémon storage (never the inventory), returns every borrowed item to the item storage
  and lends that preset's items out again. Taking a Pokémon out of its row empties the row once the Pokémon leaves
  the cursor; that is how a row is cleared. A Pokémon that left the Pokémon storage while another preset was on (taken
  into the inventory, say) stays in its row when you switch back: the row is red with the Pokémon's faint silhouette
  and keeps the item it borrows, and the Pokémon comes back with a later switch once it is in the Pokémon storage
  again. Putting another Pokémon in the row replaces it. The PC is not used. A new preset starts empty, so adding
  one puts the whole party into the Pokémon storage; deleting one switches to its neighbour first. Up to 18 presets;
  switching, adding and deleting are refused during a battle, while the cursor holds an item, or when the Pokémon
  storage has no room for the Pokémon leaving the party.
- **Storages:** two 54-slot chests of your own, shown as a large chest. The **Pokémon storage** takes only itemized
  Pokémon; beside it the column itemizes and places Pokémon as in the inventory. In the **item storage**, items (and
  items Pokémon borrow from it) wear a small chest in the corner and before their name. Pick one up there and click a
  held-item slot to lend it: the Pokémon really holds it, so battles and evolutions see it, and it is gone from the
  storage while lent. Taking it off a Pokémon puts it back in the storage, not the inventory. An item placed in the
  inventory is taken out of the storage and loses the mark.
- **Missing:** a Pokémon frame turns red, with a faint silhouette, while its Pokémon is on the cursor or after a
  switch could not find it in the Pokémon storage. A held-item slot turns red when its preset lends an item the
  Pokémon is not holding (a battle used it up, or the storage had none left when switching). Hover either to see why.

Held items cannot be changed during a battle, and Cobblemon's held-item tags (`cobblemon:held/blacklisted_items_to_hold`,
`cobblemon:held/whitelisted_items_to_hold`) apply. The column is not shown in the creative inventory. With EMI
installed, EMI keeps its side panels clear of the column.

## Support

Every item or Pokémon that moves in or out of a storage leaves a server log line, in the same shape as Cobblemon To
Item's `[pokemon-transfer]` lines:

```
[party-storage] player=<name> playerUuid=<uuid> action=<action> what=<item id xN [stored] | pokemon=<uuid> species=<id>> from=<where> to=<where>
```

Actions: `deposit`/`withdraw` (storage windows, found by comparing the storage before and after each click),
`lend`/`return` (held-item slots), `switch_out`/`switch_in`/`switch_return`/`switch_lend` (preset switches, beside the
`[party-preset]` summary line), `leak_return`/`leak_unmark`, and `battle_take`/`battle_give` (with `_kept` when
`consumeStoredItemsInBattle` is off).

Operators (level 2) can look a player up, online or not, with `/partystorage <player> items|pokemon|presets`: the item
storage (plus the items the party borrows, when online), the Pokémon storage with levels and held items, and each
preset's rows with the item they lend and whether they are missing. Pokémon UUIDs click to copy, for searching the log.

## Installation

Install the same version on **both client and server**. Requires Minecraft 1.21.1, Java 21, Fabric Loader 0.19.5+,
Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20+ and Cobblemon 1.8.1 (below 1.9.0). Mod Menu and EMI
are optional; Cobblemon To Item 0.1.7+ is required. Cobblemon has no packet for a client to change a held item, so the server half makes that change; on a
server without this mod the column only shows the party; slot interactions need the server half.

## Settings

Mod Menu opens the client settings: **Party column** (show the column) and **Pokémon tooltip**, both On by default.
Done saves `config/cobblemon_party_sidebar.json`.

The server reads `config/cobblemon_party_sidebar_server.json` at startup. `consumeStoredItemsInBattle` (default `true`)
lets a battle use up or lose a borrowed storage item like any held item; `false` leaves borrowed items on the Pokemon.
Presets and storage contents are saved per player in the world's `data/cobblemon_party_sidebar.dat`.

## License

[MIT](LICENSE). Build from `works`: `./gradlew :cobblemon-party-sidebar:build`.
