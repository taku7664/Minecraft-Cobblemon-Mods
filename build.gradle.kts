plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("dev.architectury.loom") version "1.17.493" apply false
}

allprojects {
    group = property("maven_group")!!

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.wispforest.io/")
        maven("https://api.modrinth.com/maven")
        maven("https://artefacts.cobblemon.com/releases/")
    }
}

// MCC requires Mega Showdown, which needs Architectury and Accessories, and Accessories in turn needs owo-lib.
// None of them is an MCC dependency beyond that: they only make development runs of the MCC mods start, and are
// never bundled. Modrinth version IDs are loader-specific; owo comes from Wisp's Maven so its Endec libraries
// resolve.
val megaShowdownRuntimeProjects = setOf(
    "more-cobblemon-contents",
    "more-cobblemon-contents-battle-tower",
    "more-cobblemon-contents-pvp",
    "more-cobblemon-contents-battle-factory",
    "more-cobblemon-contents-league-challenge",
)

subprojects {
    if (name !in megaShowdownRuntimeProjects) return@subprojects
    plugins.withId("dev.architectury.loom") {
        dependencies {
            "modRuntimeOnly"("maven.modrinth:cobblemon-mega-showdown:${property("mega_showdown_version_id")}")
            "modRuntimeOnly"("maven.modrinth:architectury-api:${property("architectury_api_version_id")}")
            "modRuntimeOnly"("maven.modrinth:accessories:${property("accessories_version_id")}")
            "modRuntimeOnly"("io.wispforest:owo-lib:${property("accessories_owo_lib_version")}")
        }
    }
}
