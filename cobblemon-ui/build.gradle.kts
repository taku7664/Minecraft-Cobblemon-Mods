plugins {
    id("dev.architectury.loom")
    kotlin("jvm")
}

version = property("cobblemon_ui_version")!!
group = "jbro.cobblemon"

base { archivesName.set("cobblemon-ui") }

repositories {
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://maven.shedaniel.me/")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modCompileOnly("com.cobblemon:mod:${property("cobblemon_maven_version")}") {
        isTransitive = false
    }
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")

    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    modCompileOnly("me.shedaniel.cloth:cloth-config-fabric:15.0.140")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.jar {
    from("THIRD_PARTY_LICENSE_CobblemonExtendedBattleUI")
}

loom {
    accessWidenerPath.set(file("src/main/resources/cobblemon_ui.accesswidener"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

tasks.test { enabled = false }

val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Cobblemon UI unit tests without the Gradle test worker."
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
