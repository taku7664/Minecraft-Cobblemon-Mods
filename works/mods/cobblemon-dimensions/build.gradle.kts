plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("cobblemon_dimensions_version")!!
group = "jbro.cobblemon"

base { archivesName.set("cobblemon-dimensions") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    // Ultra Wormholes check the League rank; the mod still runs without League Challenge (anyone may enter).
    compileOnly(project(path = ":more-cobblemon-contents-league-challenge", configuration = "namedElements")) { isTransitive = false }
    // Development runs only: the worlds need Terralith, and the spawn pools Cobblemon. Terralith comes from the dev
    // server with Lithostitched, which nests Apollib, which nests json5; development runs do not unpack nested jars,
    // so they are unpacked here, mods to the mod runtime and plain libraries to the classpath.
    val serverMods = rootProject.file("../develop-product/server/mods")
    // `-PnoTerralith` leaves it out, to see the dimensions built from vanilla terrain.
    val worldgenMods = if (project.hasProperty("noTerralith")) emptyList()
        else serverMods.listFiles { file -> file.name.startsWith("Terralith_") || file.name.startsWith("lithostitched-") }.orEmpty().toList()
    modLocalRuntime(files(worldgenMods))
    fun nested(jar: File): List<File> = zipTree(jar).matching { include("META-INF/jars/*.jar") }.files.flatMap { listOf(it) + nested(it) }
    worldgenMods.flatMap(::nested).forEach { jar ->
        if (zipTree(jar).matching { include("fabric.mod.json") }.isEmpty) localRuntime(files(jar)) else modLocalRuntime(files(jar))
    }
    modLocalRuntime("com.cobblemon:fabric:${property("cobblemon_maven_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
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
    accessWidenerPath.set(file("src/main/resources/cobblemon_dimensions.accesswidener"))
    runs {
        // `runCapture`: makes a fresh world, photographs the portals, a wormhole and each dimension, then quits.
        register("capture") {
            client()
            name("Dimension capture")
            property("cobblemon_dimensions.capture", "true")
            runDir("run")
        }
    }
}

tasks.test { enabled = false }

// The worldgen tests read Terralith from the dev server's mods folder.
val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs JUnit tests without Gradle's broken Windows test worker path."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    systemProperty("cobblemon_dimensions.server_mods", rootProject.file("../develop-product/server/mods").absolutePath)
    args("execute")
    sourceSets.test.get().output.classesDirs.files.forEach {
        args("--scan-class-path=${it.absolutePath}")
    }
    args("--fail-if-no-tests", "--details=summary")
}

tasks.check { dependsOn(unitTest) }
