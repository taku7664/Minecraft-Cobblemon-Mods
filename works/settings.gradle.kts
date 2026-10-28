pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

rootProject.name = "Cobblemon Mods"

include(
    "better-cobblemon-battlecam",
    "better-cobblemon-music",
    "better-battle-presentation",
    "cobblemon-ui",
    "cobblemon-custom-species",
    "cobblemon-dimensions",
    "cobblemon-npc",
    "cobblemon-party-sidebar",
    "cobblemon-to-item",
    "ppakemon-client-setup",
    "font-glyph-race-fix",
    "jbro-policy",
    "more-cobblemon-contents",
    "more-cobblemon-contents-battle-tower",
    "more-cobblemon-contents-pvp",
    "more-cobblemon-contents-battle-factory",
    "more-cobblemon-contents-league-challenge",
    "more-cobblemon-contents-pwt",
    "pokefusion",
    "simple-trash-can"
)

// The mods live under mods/; their project names stay the folder names.
rootProject.children.forEach { it.projectDir = file("mods/${it.name}") }
