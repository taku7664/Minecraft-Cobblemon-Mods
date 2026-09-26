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
