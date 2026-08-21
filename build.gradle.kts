plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("fabric-loom") version "1.17.19" apply false
}

allprojects {
    group = property("maven_group")!!

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://api.modrinth.com/maven")
    }
}
