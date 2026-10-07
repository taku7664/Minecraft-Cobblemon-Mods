plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("cobblemon_npc_version")!!
group = "jbro.cobblemon"

base { archivesName.set("cobblemon-npc") }

repositories { maven("https://maven.terraformersmc.com/releases/") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    // The UI kit needs Cobblemon on the client; the NPCs themselves do not touch it.
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    implementation("com.google.code.gson:gson:2.11.0")
    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    // Bundled like More Cobblemon Contents does: the dialogue box and editors draw with the UI kit, and a dedicated
    // server skips the client-only kit.
    implementation(project(path = ":cobblemon-ui", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":cobblemon-ui", configuration = "namedElements")) { isTransitive = false }
    include(project(":cobblemon-ui")) { isTransitive = false }
    // Optional: a dialogue turns its camera when a player has it; development runs include it.
    compileOnly(project(path = ":better-cobblemon-battlecam", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":better-cobblemon-battlecam", configuration = "namedElements")) { isTransitive = false }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
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

tasks.test { enabled = false }

listOf("jar", "sourcesJar").forEach { taskName ->
    tasks.named<org.gradle.jvm.tasks.Jar>(taskName) {
        from("LICENSE") { rename { "LICENSE_cobblemon_npc" } }
        from("NOTICE.md")
    }
}

val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Cobblemon NPC JUnit tests without the Gradle test worker."
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
