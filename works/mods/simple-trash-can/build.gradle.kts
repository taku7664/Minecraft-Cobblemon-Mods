plugins {
    id("dev.architectury.loom")
}

version = property("simple_trash_can_version")!!
group = "jbro.minecraft"

base { archivesName.set("simple-trash-can") }

repositories { maven("https://maven.terraformersmc.com/releases/") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    // Optional: EMI keeps its side panels clear of the trash panel when it is installed.
    modCompileOnly("dev.emi:emi-fabric:1.1.24+1.21.1:api")
}

val modVersion = version.toString()

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

loom {
    accessWidenerPath.set(file("src/main/resources/simple_trash_can.accesswidener"))
}

tasks.test { enabled = false }
