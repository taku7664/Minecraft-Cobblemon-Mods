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
    "cobblemon-battle-ui",
    "cobblemon-ui-kit",
    "cobblemon-custom-species",
    "font-glyph-race-fix",
    "jbro-policy",
    "more-cobblemon-contents",
    "more-cobblemon-contents-battle-tower",
    "more-cobblemon-contents-pvp",
    "more-cobblemon-contents-battle-factory",
    "more-cobblemon-contents-league-challenge",
    "player-popup-emotes",
    "pokefusion",
    "rounding-block",
    "simple-myroom"
)
