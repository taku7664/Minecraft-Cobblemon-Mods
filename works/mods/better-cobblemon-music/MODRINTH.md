# Better Cobblemon Music

Bring contextual music to your Cobblemon adventures. Better Cobblemon Music selects tracks for exploration, battles and menus, with smooth transitions and customizable mappings.

## Features

- Field music based on dimension, biome, underground exploration and in-game day or night.
- Battle music for wild Pokémon, trainers, PvP, legendary Pokémon, Ultra Beasts and alpha Pokémon, with optional Pokémon-specific tracks.
- Music for the title screen and supported mod screens.
- Adjustable fades, track spacing, playback order and volume.
- A brief now-playing title when the BGM changes, plus `/music` to check the current track.
- Battle hit sounds, a repeating warning when your active Pokémon reaches 20% HP or below, and muffled BGM when your last available Pokémon drops to half HP or below.
- Underwater muffling and reverb applied to this mod's BGM.
- Optional integration with More Cobblemon Contents battles and hub screens.

Battle music takes priority over mapped screen music, which takes priority over field music. Available tracks and default mappings come from your active music resource pack.

## Requirements and setup

**Client-side only.** Install on the player's client; no server installation is needed.

- Minecraft Java Edition **1.21.1**, Fabric Loader **0.19.3+**, Java **21+**.
- **Fabric API** and **Cobblemon 1.8.1 or newer, below 1.9.0**.
- **Mod Menu + Cloth Config** are optional, but both are needed for the configuration screen.
- A **compatible music resource pack with a Better Cobblemon Music catalog** is required for audio. The mod JAR contains no music or sound-effect recordings.

Put the mod JAR in `mods`, enable a compatible music pack in Minecraft's resource-pack menu, then select its base catalog in **Mod Menu → Better Cobblemon Music → Configure**. A pack containing only OGG files is not enough without the required catalog and mappings.

## Configuration

Choose playlists for field, battle, Pokémon, content and screen mappings. Adjust playback timing, volume, hit sounds, HP effects, underwater effect strength and the now-playing notification. The screen supports English and Korean.

Without the configuration screen, edit `config/better_cobblemon_music/settings.json` for playback and effects, and `overrides.json` for mapping overrides. Use `/bcm reload` to reload catalogs and settings. Both commands are client commands and need no server permissions.

## License

The mod code and its icon are provided under the **MIT License**. External music packs and user-added recordings retain their own licenses.

[Source code](https://github.com/taku7664/Minecraft-Cobblemon-Mods/tree/main/works/mods/better-cobblemon-music) · [Report an issue](https://github.com/taku7664/Minecraft-Cobblemon-Mods/issues)
