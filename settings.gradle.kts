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
    "cobblemon-custom-species",
    "more-battle-content",
    "more-battle-content-better-ai",
    "player-popup-emotes",
    "pokefusion",
    "rounding-block",
    "simple-myroom"
)
