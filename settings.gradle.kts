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
    "better-cobblemon-music",
    "better-battle-presentation",
    "cobblemon-custom-species",
    "more-battle-content",
    "more-battle-content-better-ai",
    "pokefusion",
    "rounding-block",
    "simple-myroom"
)
