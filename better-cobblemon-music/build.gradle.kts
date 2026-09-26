plugins {
    id("dev.architectury.loom")
}

repositories {
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://maven.shedaniel.me/")
}

version = property("better_cobblemon_music_version")!!

group = "jbro.cobblemon"

base { archivesName.set("better-cobblemon-music") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("maven.modrinth:cobblemon:${property("cobblemon_version_id")}")
    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    modCompileOnly("me.shedaniel.cloth:cloth-config-fabric:15.0.140")

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
    description = "Runs Better Cobblemon Music JUnit tests without the Gradle test worker."
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

val generatedMusicResourcePack = layout.buildDirectory.dir("generated/music-resource-pack")

val generateMusicResourcePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Builds and validates the official Better Cobblemon Music resource pack directory."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.bettermusic.resource.MusicResourcePackBuildTool")
    val sourceDirectory = layout.projectDirectory.dir("resource-pack/src")
    val catalogLayout = layout.projectDirectory.file("resource-pack/catalog-layout.json")
    inputs.dir(sourceDirectory)
    inputs.file(catalogLayout)
    outputs.dir(generatedMusicResourcePack)
    args(
        sourceDirectory.asFile.absolutePath,
        catalogLayout.asFile.absolutePath,
        generatedMusicResourcePack.get().asFile.absolutePath
    )
}

val musicResourcePackZip by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages the validated official Better Cobblemon Music resource pack."
    dependsOn(generateMusicResourcePack)
    from(generatedMusicResourcePack)
    archiveFileName.set("cobleserver-music-resourcepack-${modVersion}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.check { dependsOn(generateMusicResourcePack) }
tasks.assemble { dependsOn(musicResourcePackZip) }
