pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "Cobblemon Mods"

include(
    "better-cobblemon-music-more-battle-content",
    "better-cobblemon-music",
    "better-battle-presentation",
    "cobblemon-custom-species",
    "more-battle-content",
    "more-battle-content-better-ai",
    "simple-myroom"
)
