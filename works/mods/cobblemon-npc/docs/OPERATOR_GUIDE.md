# Cobblemon NPC

NPCs that talk in the Cobblemon UI dialogue box (`cobblemon_npc`). A talk is a set of nodes: lines to read, answers
to pick, branches on conditions, and commands run as the talk moves on, so a line and a command follow each other
naturally ("Here to take on the Battle Tower?" → `/mcc terminal tower`). The server runs every step; the client only
shows pages and reports the answer. The mod bundles the UI kit (`cobblemon-ui`) the way More Cobblemon Contents does
and goes on both the client and the server. Unit tests: `gradlew :cobblemon-npc:unitTest`.

## For operators

- `/npc wand` gives the NPC wand (permission level 2). Right-click a block to place an NPC; right-click an NPC with
  the wand to set its name, skin and dialogue, edit the dialogue, or remove it.
- A skin is a player name (`Steve`), an RCT Trainers+ trainer (`rct:clerk`, short for
  `rctmod:textures/trainers/single/clerk.png`; the **RCT ▶** button browses the enabled pack with a preview), or
  any `namespace:path.png` skin texture. A texture skin's slim or wide model is read from the image; without the
  pack the NPC wears a default skin.
- NPCs do not move, take damage or despawn. `/kill` still removes one.
- `/npc talk <players> <dialogue> [node]` opens a dialogue without an NPC (command blocks, other mods' scripts, or a
  dialogue's own `@server` command). `/npc end <players>` closes it. `/npc reload` reads the files again.

## Dialogue files

`config/cobblemon_npc/dialogues/<id>.json`, written by the in-game editor or by hand. In the editor, branches
and commands are rows: a branch picks its condition kind (tag, permission, command or free text), and command
rows and command conditions complete as they are typed, like a command block; a command row's **OP** switch is
the `@server` prefix. The first start writes the
example `tower_guide.json`.

```json
{
  "speaker": "Tower Guide",
  "start": "greet",
  "nodes": {
    "greet": {
      "lines": ["Hi {player},", "Here to take on the Battle Tower?"],
      "choices": [{ "text": "Yes!", "next": "check" }, { "text": "No", "next": null }]
    },
    "check": { "branches": [{ "if": "cmd:mcc tower access", "next": "open" }], "next": "not_yet" },
    "not_yet": { "lines": ["You don't seem ready yet..."] },
    "open": { "lines": ["Opening the terminal!"], "commands": ["/mcc terminal tower"] }
  }
}
```

- A node with `lines` shows them one page each. After the last page the player picks a choice, or the node moves on
  by itself: to the first branch whose condition holds, otherwise to `next`. No target ends the talk.
- A node without lines is passed straight through, so a node of only `commands` is an action mid-talk.
- `commands` run when the node is left, after the next page is shown or the box has closed, so a command that opens
  a screen lands on top. `/cmd` runs as the player with the player's own rights; `@server /cmd` runs as the player
  with operator rights and no feedback.
- Conditions (`if` on a branch or a choice): `tag:<name>`, `perm:<level>`, `cmd:<command>` (succeeds with a
  positive result, e.g. `cmd:execute if score @s wins matches 10..`), negated with `!`, joined with `&&`.
- `{player}` and `{npc}` are filled into lines, choices and commands. `speaker` and `skin` are used when no NPC
  opened the talk.
