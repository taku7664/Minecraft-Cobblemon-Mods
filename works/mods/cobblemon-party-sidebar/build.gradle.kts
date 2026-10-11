plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("cobblemon_party_sidebar_version")!!
group = "jbro.cobblemon"

base { archivesName.set("cobblemon-party-sidebar") }

repositories { maven("https://maven.terraformersmc.com/releases/") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    // Optional: EMI keeps its side panels clear of the party column when it is installed.
    modCompileOnly("dev.emi:emi-fabric:1.1.24+1.21.1:api")
}

val modVersion = version.toString()

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

loom {
    accessWidenerPath.set(file("src/main/resources/cobblemon_party_sidebar.accesswidener"))
}

tasks.test { enabled = false }
