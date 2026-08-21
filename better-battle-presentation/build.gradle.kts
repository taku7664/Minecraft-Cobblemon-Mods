plugins {
    id("fabric-loom")
}

version = property("better_battle_presentation_version")!!

group = "jbro.cobblemon"

base { archivesName.set("cobblemon-better-battle-presentation") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("maven.modrinth:cobblemon:${property("cobblemon_version_id")}")
    modImplementation("maven.modrinth:cobblemon-mega-showdown:${property("mega_showdown_version_id")}")
    modImplementation("maven.modrinth:architectury-api:${property("architectury_api_version_id")}")
    modImplementation("maven.modrinth:accessories:${property("accessories_version_id")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
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

tasks.test { enabled = false }

val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Better Battle Presentation JUnit tests without the Gradle test worker."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args("execute")
    sourceSets.test.get().output.classesDirs.files.forEach {
        args("--scan-class-path=${it.absolutePath}")
    }
    args("--fail-if-no-tests", "--details=summary")
}

tasks.check { dependsOn(unitTest) }
